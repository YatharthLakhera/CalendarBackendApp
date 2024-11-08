package com.calendar.app.controller;

import com.calendar.app.db.UserDefaultAvailability;
import com.calendar.app.models.api.UserRequest;
import com.calendar.app.models.api.UserResponse;
import com.calendar.app.models.api.UserUpdateRequest;
import com.calendar.app.services.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class UserController {

    @Autowired private UserService userService;

    @GetMapping("/user")
    public UserResponse getUserFor(@RequestHeader("userId") UUID userId) {
        return userService.getUserResponseFor(userId);
    }

    @GetMapping("/user/search")
    public UserResponse searchUserFor(@RequestParam("email") String email) {
        return userService.searchFor(email);
    }

    @PostMapping("/user")
    public UserResponse addUserFor(@Valid @RequestBody UserRequest userRequest) {
        return userService.addUserFor(userRequest);
    }

    @PutMapping("/user")
    public UserResponse updateUserFor(@RequestHeader("userId") UUID userId, @Valid @RequestBody UserUpdateRequest request) {
        return userService.updateUserFor(userId, request);
    }
}
