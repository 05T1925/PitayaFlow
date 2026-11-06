package dev.pitayaflow.backend;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pf.role",havingValue="worker")
@RocketMQMessageListener(topic="pf-s1-probe",consumerGroup="pf-s1-probe-worker")
final class ProbeWorker implements RocketMQListener<String> {
    private final JobService jobs;
    ProbeWorker(JobService jobs) { this.jobs=jobs; }
    @Override public void onMessage(String id) { jobs.run(id); }
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    void recover() { jobs.expireTerminated(); for (String id:jobs.recoverable()) jobs.run(id); }
}
