package com.dnlabz.flowlens.starter.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * One node in the static call tree produced by {@link StaticCallAnalyzer}.
 *
 * <p>INTERNAL nodes represent application-level method calls that can be
 * recursively explored.  All other kinds represent well-known external systems.
 */
public class CallNode {

    public enum Kind {
        ENTRY,          // root – the entry-point method itself
        INTERNAL,       // application-level call (recurse into it)
        REST_CALL,      // RestTemplate / WebClient / Feign / HttpClient
        KAFKA_PRODUCE,  // KafkaTemplate.send / KafkaProducer
        REDIS,          // RedisTemplate / Jedis / Lettuce / Redisson
        DATABASE,       // JPA Repository / JdbcTemplate / EntityManager
        CADENCE,        // Uber Cadence workflow / activity stubs
        TEMPORAL,       // Temporal workflow / activity stubs
        ELASTICSEARCH,  // Elasticsearch operations
        MONGODB,        // MongoTemplate
        GRPC,           // gRPC stubs
        MESSAGING,      // RabbitMQ / SQS / SNS / generic AMQP
    }

    private final String     simpleClass;   // display label (simple name or system label)
    private final String     method;        // method name
    private final Kind       kind;
    private final String     detail;        // optional extra hint (topic, URL fragment …)
    private final List<CallNode> children = new ArrayList<>();

    public CallNode(String simpleClass, String method, Kind kind, String detail) {
        this.simpleClass = simpleClass;
        this.method      = method;
        this.kind        = kind;
        this.detail      = detail;
    }

    public String          getSimpleClass() { return simpleClass; }
    public String          getMethod()      { return method;      }
    public Kind            getKind()        { return kind;        }
    public String          getDetail()      { return detail;      }
    public List<CallNode>  getChildren()    { return children;    }

    public void addChild(CallNode child)    { children.add(child); }
}
