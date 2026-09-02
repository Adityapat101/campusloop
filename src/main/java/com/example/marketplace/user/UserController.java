package com.example.marketplace.user;

import java.net.URI;
import java.util.List;

import com.example.marketplace.common.ConflictException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    List<UserAccount> all() {
        return users.findAll(Sort.by("name"));
    }

    @PostMapping
    ResponseEntity<UserAccount> create(@Valid @RequestBody UserRequest request) {
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("A user with that email already exists.");
        }
        UserAccount saved = users.save(new UserAccount(
                request.name().trim(), request.email().trim().toLowerCase(), request.campus().trim()));
        return ResponseEntity.created(URI.create("/api/users/" + saved.getId())).body(saved);
    }
}
