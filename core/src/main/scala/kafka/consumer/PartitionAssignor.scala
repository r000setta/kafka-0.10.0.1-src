/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.consumer

import org.I0Itec.zkclient.ZkClient
import kafka.common.TopicAndPartition
import kafka.utils.{Pool, CoreUtils, ZkUtils, Logging}

import scala.collection.mutable

trait PartitionAssignor {

  /**
   * Assigns partitions to consumer instances in a group.
   * @return An assignment map of partition to this consumer group. This includes assignments for threads that belong
   *         to the same consumer group.
   */
  def assign(ctx: AssignmentContext): Pool[String, mutable.Map[TopicAndPartition, ConsumerThreadId]]

}

object PartitionAssignor {
  def createInstance(assignmentStrategy: String) = assignmentStrategy match {
    case "roundrobin" => new RoundRobinAssignor()
    case _ => new RangeAssignor()
  }
}

//在平衡時构造当前消费者的分区分配上下文信息，用于分配算法
class AssignmentContext(group: String, val consumerId: String, excludeInternalTopics: Boolean, zkUtils: ZkUtils) {
  val myTopicThreadIds: collection.Map[String, collection.Set[ConsumerThreadId]] = {
    val myTopicCount = TopicCount.constructTopicCount(group, consumerId, zkUtils, excludeInternalTopics)
    myTopicCount.getConsumerThreadIdsPerTopic
  }

  //根据订阅的主题得到可用的主题
  val partitionsForTopic: collection.Map[String, Seq[Int]] =
    zkUtils.getPartitionsForTopics(myTopicThreadIds.keySet.toSeq)
  //订阅指定主题的消费者线程
  val consumersForTopic: collection.Map[String, List[ConsumerThreadId]] =
    zkUtils.getConsumersPerTopic(group, excludeInternalTopics)
  //消费组的消费者成员列表
  val consumers: Seq[String] = zkUtils.getConsumersInGroup(group).sorted
}

/**
 * The round-robin partition assignor lays out all the available partitions and all the available consumer threads. It
 * then proceeds to do a round-robin assignment from partition to consumer thread. If the subscriptions of all consumer
 * instances are identical, then the partitions will be uniformly distributed. (i.e., the partition ownership counts
 * will be within a delta of exactly one across all consumer threads.)
 *
 * (For simplicity of implementation) the assignor is allowed to assign a given topic-partition to any consumer instance
 * and thread-id within that instance. Therefore, round-robin assignment is allowed only if:
 * a) Every topic has the same number of streams within a consumer instance
 * b) The set of subscribed topics is identical for every consumer instance within the group.
 */

class RoundRobinAssignor() extends PartitionAssignor with Logging {

  //分配算法，根据AssignmentContext进行分配,针对消费组的所有消费者，返回所有消费者的分区分配结果
  def assign(ctx: AssignmentContext) = {

    val valueFactory = (topic: String) => new mutable.HashMap[TopicAndPartition, ConsumerThreadId]
    val partitionAssignment =
      new Pool[String, mutable.Map[TopicAndPartition, ConsumerThreadId]](Some(valueFactory))

    if (ctx.consumersForTopic.size > 0) {
      // check conditions (a) and (b)
      val (headTopic, headThreadIdSet) = (ctx.consumersForTopic.head._1, ctx.consumersForTopic.head._2.toSet)
      ctx.consumersForTopic.foreach { case (topic, threadIds) =>
        val threadIdSet = threadIds.toSet
        require(threadIdSet == headThreadIdSet,
          "Round-robin assignment is allowed only if all consumers in the group subscribe to the same topics, " +
            "AND if the stream counts across topics are identical for a given consumer instance.\n" +
            "Topic %s has the following available consumer streams: %s\n".format(topic, threadIdSet) +
            "Topic %s has the following available consumer streams: %s\n".format(headTopic, headThreadIdSet))
      }

      val threadAssignor = CoreUtils.circularIterator(headThreadIdSet.toSeq.sorted)

      info("Starting round-robin assignment with consumers " + ctx.consumers)
      val allTopicPartitions = ctx.partitionsForTopic.flatMap { case (topic, partitions) =>
        info("Consumer %s rebalancing the following partitions for topic %s: %s"
          .format(ctx.consumerId, topic, partitions))
        partitions.map(partition => {
          TopicAndPartition(topic, partition)
        })
      }.toSeq.sortWith((topicPartition1, topicPartition2) => {
        /*
         * Randomize the order by taking the hashcode to reduce the likelihood of all partitions of a given topic ending
         * up on one consumer (if it has a high enough stream count).
         */
        topicPartition1.toString.hashCode < topicPartition2.toString.hashCode
      })

      allTopicPartitions.foreach(topicPartition => {
        val threadId = threadAssignor.next()
        // record the partition ownership decision
        val assignmentForConsumer = partitionAssignment.getAndMaybePut(threadId.consumer)
        assignmentForConsumer += (topicPartition -> threadId)
      })
    }

    // assign Map.empty for the consumers which are not associated with topic partitions
    ctx.consumers.foreach(consumerId => partitionAssignment.getAndMaybePut(consumerId))
    partitionAssignment
  }
}

/**
 * Range partitioning works on a per-topic basis. For each topic, we lay out the available partitions in numeric order
 * and the consumer threads in lexicographic order. We then divide the number of partitions by the total number of
 * consumer streams (threads) to determine the number of partitions to assign to each consumer. If it does not evenly
 * divide, then the first few consumers will have one extra partition. For example, suppose there are two consumers C1
 * and C2 with two streams each, and there are five available partitions (p0, p1, p2, p3, p4). So each consumer thread
 * will get at least one partition and the first consumer thread will get one extra partition. So the assignment will be:
 * p0 -> C1-0, p1 -> C1-0, p2 -> C1-1, p3 -> C2-0, p4 -> C2-1
 */
class RangeAssignor() extends PartitionAssignor with Logging {

  def assign(ctx: AssignmentContext) = {
    val valueFactory = (topic: String) => new mutable.HashMap[TopicAndPartition, ConsumerThreadId]
    val partitionAssignment =
      new Pool[String, mutable.Map[TopicAndPartition, ConsumerThreadId]](Some(valueFactory))
    for (topic <- ctx.myTopicThreadIds.keySet) {
      val curConsumers = ctx.consumersForTopic(topic)
      val curPartitions: Seq[Int] = ctx.partitionsForTopic(topic)

      val nPartsPerConsumer = curPartitions.size / curConsumers.size
      val nConsumersWithExtraPart = curPartitions.size % curConsumers.size

      info("Consumer " + ctx.consumerId + " rebalancing the following partitions: " + curPartitions +
        " for topic " + topic + " with consumers: " + curConsumers)

      for (consumerThreadId <- curConsumers) {
        val myConsumerPosition = curConsumers.indexOf(consumerThreadId)
        assert(myConsumerPosition >= 0)
        val startPart = nPartsPerConsumer * myConsumerPosition + myConsumerPosition.min(nConsumersWithExtraPart)
        val nParts = nPartsPerConsumer + (if (myConsumerPosition + 1 > nConsumersWithExtraPart) 0 else 1)

        /**
         *   Range-partition the sorted partitions to consumers for better locality.
         *  The first few consumers pick up an extra partition, if any.
         */
        if (nParts <= 0)
          warn("No broker partitions consumed by consumer thread " + consumerThreadId + " for topic " + topic)
        else {
          for (i <- startPart until startPart + nParts) {
            val partition = curPartitions(i)
            info(consumerThreadId + " attempting to claim partition " + partition)
            // record the partition ownership decision
            val assignmentForConsumer = partitionAssignment.getAndMaybePut(consumerThreadId.consumer)
            assignmentForConsumer += (TopicAndPartition(topic, partition) -> consumerThreadId)
          }
        }
      }
    }

    // assign Map.empty for the consumers which are not associated with topic partitions
    ctx.consumers.foreach(consumerId => partitionAssignment.getAndMaybePut(consumerId))
    partitionAssignment
  }
}
