package com.calendar.app.db.repository;

import com.calendar.app.db.entity.UserDetails;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserDetails, UUID> {

    Optional<UserDetails> findByEmail(String email);

    List<UserDetails> findByEmailIn(List<String> emails);
}
