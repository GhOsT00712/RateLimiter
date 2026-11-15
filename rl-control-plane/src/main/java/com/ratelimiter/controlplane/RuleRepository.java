package com.ratelimiter.controlplane;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RuleRepository extends MongoRepository<Rule, String> {
    List<Rule> findByServiceId(String serviceId);
}
