package my;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import scala.sys.Prop;

import java.util.Collection;
import java.util.Collections;
import java.util.Properties;

public class NewConsumer {
    public static void main(String[] args) {
        Properties props=new Properties();
        props.put("group.id","test");
        //
        KafkaConsumer<Integer,String> consumer=new KafkaConsumer<Integer, String>(props);
        consumer.subscribe(Collections.singleton());

    }
}
