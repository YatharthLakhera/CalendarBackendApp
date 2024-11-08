package com.calendar.app.services;

import com.calendar.app.db.entity.UserDetails;
import com.calendar.app.db.repository.UserRepository;
import com.calendar.app.exceptions.UserNotFound;
import com.calendar.app.models.api.UserRequest;
import com.calendar.app.models.api.UserResponse;
import com.calendar.app.models.api.UserUpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class UserService {

    @Autowired private UserRepository userRepository;

    public UserResponse getUserResponseFor(final UUID userId) {
        return new UserResponse(getUserFor(userId));
    }

    public UserResponse searchFor(final String email) {
        return new UserResponse(getUserFor(email));
    }

    public UserResponse addUserFor(UserRequest userRequest) {
        UserDetails userDetails = new UserDetails(userRequest);
        userDetails = createOrUpdate(userDetails);
        log.info("addUser : {}", userDetails);
        return new UserResponse(userDetails);
    }

    public UserResponse updateUserFor(final UUID userId, UserUpdateRequest request) {
        UserDetails userDetails = getUserFor(userId);
        userDetails.setName(request.getName());
        return new UserResponse(createOrUpdate(userDetails));
    }

    protected List<UserDetails> getUserFor(final List<String> emails) {
        return userRepository.findByEmailIn(emails);
    }

    /**
     * Fetching {@link UserDetails} based on {@param email} and if not found
     * throwing exception {@link UserNotFound}
     * This function is only accessible in service package
     * @param email
     * @return
     */
    protected UserDetails getUserFor(final String email) {
        Optional<UserDetails> userDetails = userRepository.findByEmail(email);
        if (userDetails.isPresent()) {
            return userDetails.get();
        }
        throw new UserNotFound();
    }

    /**
     * Fetching {@link UserDetails} based on {@param userId} and if not found
     * throwing exception {@link UserNotFound}
     * This function is only accessible in service package
     * @param userId
     * @return
     */
    protected UserDetails getUserFor(final UUID userId) {
        Optional<UserDetails> userDetails = userRepository.findById(userId);
        if (userDetails.isPresent()) {
            return userDetails.get();
        }
        throw new UserNotFound();
    }

    /**
     * This function is to save or update the user details entity which is exposed
     * to other classes in service package
     * @param userDetails
     * @return
     */
    protected UserDetails createOrUpdate(UserDetails userDetails) {
        return userRepository.save(userDetails);
    }
}
