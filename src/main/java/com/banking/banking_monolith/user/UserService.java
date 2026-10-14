package com.banking.banking_monolith.user;

import com.banking.banking_monolith.exception.ResourceNotFoundException;
import com.banking.banking_monolith.notification.NotificationService;
import com.banking.banking_monolith.notification.NotificationType;
import com.banking.banking_monolith.user.dto.RegisterRequest;
import com.banking.banking_monolith.user.dto.UpdateUserRequest;
import com.banking.banking_monolith.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

// Handles all user-related business logic
// Also implements UserDetailsService so Spring Security can load users during login
@Service
@RequiredArgsConstructor
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    // Registers a new user, hashes the password, and sends a welcome notification
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already in use: " + request.getEmail());
        }

        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.USER)
                .build();

        User saved = userRepository.save(user);
        notificationService.createNotification(saved, "Welcome! Your account has been successfully created.", NotificationType.SYSTEM);

        return toResponse(saved);
    }

    // Fetches a user by ID and returns a response DTO
    public UserResponse getUserById(Long id) {
        return toResponse(findById(id));
    }

    // Updates only the first and last name of the user
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        User user = findById(id);
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        return toResponse(userRepository.save(user));
    }

    // Used by Spring Security to load user by email during authentication
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }

    // Internal helper to find a user by ID or throw an exception
    private User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    // Converts User entity to UserResponse DTO
    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
