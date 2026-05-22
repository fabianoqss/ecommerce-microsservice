package com.example.ecommerce.auth_service.repositories;

import com.example.ecommerce.auth_service.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);
}
