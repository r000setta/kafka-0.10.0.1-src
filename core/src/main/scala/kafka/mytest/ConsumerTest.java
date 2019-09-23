package kafka.mytest;

import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;

public class ConsumerTest implements Runnable{
    private KafkaStream stream;

    private int n;

    @Override
    public void run() {
        ConsumerIterator<byte[],byte[]> it=stream.iterator();
        while (it.hasNext())
            System.out.print("Thread "+n+":"+new String(it.next().message()));
    }
}
