package com.uav.platformservice.repository;

import com.uav.platformservice.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {

    /** 按用户名查询（登录时用） */
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
