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
import kafka.server.{BrokerAndInitialOffset, AbstractFetcherThread, AbstractFetcherManager}
import kafka.cluster.{BrokerEndPoint, Cluster}
import org.apache.kafka.common.protocol.SecurityProtocol
import scala.collection.immutable
import collection.mutable.HashMap
import scala.collection.mutable
import java.util.concurrent.locks.ReentrantLock
import kafka.utils.CoreUtils.inLock
import kafka.utils.ZkUtils
import kafka.utils.{ShutdownableThread, SystemTime}
import kafka.common.TopicAndPartition
import kafka.client.ClientUtils
import java.util.concurrent.atomic.AtomicInteger

/**
 *  Usage:
 *  Once ConsumerFetcherManager is created, startConnections() and stopAllConnections() can be called repeatedly
 *  until shutdown() is called.
 */
//消费者拉取线程管理器
class ConsumerFetcherManager(private val consumerIdString: String,
                             private val config: ConsumerConfig,
                             private val zkUtils : ZkUtils)
        extends AbstractFetcherManager("ConsumerFetcherManager-%d".format(SystemTime.milliseconds),
                                       config.clientId, config.numConsumerFetchers) {
  //被消费者拉取管理器管理的分区集合
  private var partitionMap: immutable.Map[TopicAndPartition, PartitionTopicInfo] = null
  private var cluster: Cluster = null
  //候选集合，即暂时找不到主副本的分区
  private val noLeaderPartitionSet = new mutable.HashSet[TopicAndPartition]
  private val lock = new ReentrantLock
  private val cond = lock.newCondition()
  private var leaderFinderThread: ShutdownableThread = null
  private val correlationId = new AtomicInteger(0)

  private class LeaderFinderThread(name: String) extends ShutdownableThread(name) {
    // thread responsible for adding the fetcher to the right broker when leader is available
    override def doWork() {
      val leaderForPartitionsMap = new HashMap[TopicAndPartition, BrokerEndPoint]
      lock.lock()
      try {
        while (noLeaderPartitionSet.isEmpty) {  //没有数据，等待
          trace("No partition for leader election.")
          cond.await()
        }

        trace("Partitions without leader %s".format(noLeaderPartitionSet))
        //向任意一个消息代理节点获取元数据，其中包含了分区的主副本
        val brokers = zkUtils.getAllBrokerEndPointsForChannel(SecurityProtocol.PLAINTEXT)
        val topicsMetadata = ClientUtils.fetchTopicMetadata(noLeaderPartitionSet.map(m => m.topic).toSet,
                                                            brokers,
                                                            config.clientId,
                                                            config.socketTimeoutMs,
                                                            correlationId.getAndIncrement).topicsMetadata
        if(logger.isDebugEnabled) topicsMetadata.foreach(topicMetadata => debug(topicMetadata.toString()))
        topicsMetadata.foreach { tmd => //主题元数据
          val topic = tmd.topic
          tmd.partitionsMetadata.foreach { pmd => //分区元数据
            val topicAndPartition = TopicAndPartition(topic, pmd.partitionId)
            //将候选集中存在主副本的分区加入结果集，并从候选集中删除
            if(pmd.leader.isDefined && noLeaderPartitionSet.contains(topicAndPartition)) {
              val leaderBroker = pmd.leader.get
              leaderForPartitionsMap.put(topicAndPartition, leaderBroker) //分区被选中
              noLeaderPartitionSet -= topicAndPartition
            }
          }
        }
      } catch {
        case t: Throwable => {
            if (!isRunning.get())
              throw t /* If this thread is stopped, propagate this exception to kill the thread. */
            else
              warn("Failed to find leader for %s".format(noLeaderPartitionSet), t)
          }
      } finally {
        lock.unlock()
      }

      try {
        //将分区加入拉取线程，BrokerAndInitialOffset指定拉取线程从何处开始拉取
        addFetcherForPartitions(leaderForPartitionsMap.map{
          case (topicAndPartition, broker) =>
            topicAndPartition -> BrokerAndInitialOffset(broker, partitionMap(topicAndPartition).getFetchOffset())}
        )
      } catch {
        case t: Throwable => {
          if (!isRunning.get())
            throw t /* If this thread is stopped, propagate this exception to kill the thread. */
          else {
            warn("Failed to add leader for partitions %s; will retry".format(leaderForPartitionsMap.keySet.mkString(",")), t)
            lock.lock()
            noLeaderPartitionSet ++= leaderForPartitionsMap.keySet
            lock.unlock()
          }
        }
      }

      shutdownIdleFetcherThreads()
      Thread.sleep(config.refreshLeaderBackoffMs)
    }
  }

  //创建拉取线程
  override def createFetcherThread(fetcherId: Int, sourceBroker: BrokerEndPoint): AbstractFetcherThread = {
    new ConsumerFetcherThread(
      "ConsumerFetcherThread-%s-%d-%d".format(consumerIdString, fetcherId, sourceBroker.id),
      config, sourceBroker, partitionMap, this)
  }

  def startConnections(topicInfos: Iterable[PartitionTopicInfo], cluster: Cluster) {
    leaderFinderThread = new LeaderFinderThread(consumerIdString + "-leader-finder-thread")
    leaderFinderThread.start()

    inLock(lock) {
      partitionMap = topicInfos.map(tpi => (TopicAndPartition(tpi.topic, tpi.partitionId), tpi)).toMap
      this.cluster = cluster
      noLeaderPartitionSet ++= topicInfos.map(tpi => TopicAndPartition(tpi.topic, tpi.partitionId))
      cond.signalAll()
    }
  }

  def stopConnections() {
    /*
     * Stop the leader finder thread first before stopping fetchers. Otherwise, if there are more partitions without
     * leader, then the leader finder thread will process these partitions (before shutting down) and add fetchers for
     * these partitions.
     */
    info("Stopping leader finder thread")
    if (leaderFinderThread != null) {
      leaderFinderThread.shutdown()
      leaderFinderThread = null
    }

    info("Stopping all fetchers")
    closeAllFetchers()

    // no need to hold the lock for the following since leaderFindThread and all fetchers have been stopped
    partitionMap = null
    noLeaderPartitionSet.clear()

    info("All connections stopped")
  }

  def addPartitionsWithError(partitionList: Iterable[TopicAndPartition]) {
    debug("adding partitions with error %s".format(partitionList))
    inLock(lock) {
      if (partitionMap != null) {
        noLeaderPartitionSet ++= partitionList
        cond.signalAll()
      }
    }
  }
}