package dev.pitayaflow.backend;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pf.role",havingValue="api",matchIfMissing=true)
final class OutboxPublisher {
    private final JdbcTemplate db;
    private final RocketMQTemplate mq;
    OutboxPublisher(JdbcTemplate db,RocketMQTemplate mq) { this.db=db; this.mq=mq; }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    void deliver() {
        for (String id:db.queryForList("SELECT job_id FROM pf_outbox WHERE delivered_at IS NULL ORDER BY id LIMIT 20",String.class)) {
            try { mq.syncSend("pf-s1-probe",id,3000); db.update("UPDATE pf_outbox SET delivered_at=NOW(3) WHERE job_id=? AND delivered_at IS NULL",id); }
            catch (Exception ignored) { break; } // DB row remains for replay after MQ recovery
        }
    }
}
