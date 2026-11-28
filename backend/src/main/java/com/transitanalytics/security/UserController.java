package com.transitanalytics.security;

import com.transitanalytics.common.NotFoundException;
import com.transitanalytics.dto.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Users", description = "The app's users (admins only)")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final Users users;
    private final PasswordEncoder encoder;

    public UserController(Users users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Operation(summary = "Every user with their role, by name")
    @GetMapping
    public List<UserDto.User> list() {
        return users.list();
    }

    @Operation(summary = "Add a user",
            description = "A name of 3 to 20 characters, no spaces, not starting with a digit, not taken (in any case); "
                    + "a password of at least 10 characters of at least two kinds (lowercase, uppercase, digits, "
                    + "others); the role ADMIN or GUEST. 400 when a rule is broken, 409 when the name is taken.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto.User create(@RequestBody UserDto.NewUser user) {
        UserRules.check(user.username(), user.password(), user.role());
        if (users.exists(user.username())) {
            throw new UserExistsException(user.username());
        }
        return users.create(user.username(), encoder.encode(user.password()), user.role());
    }

    @Operation(summary = "Remove a user", description = "Not your own account (400); 404 when there is no such user. "
            + "The user's sign-in stops working at once.")
    @DeleteMapping("/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String username, Authentication auth) {
        if (username.equals(auth.getName())) {
            throw new IllegalArgumentException("You can't delete your own account");
        }
        if (!users.delete(username)) {
            throw new NotFoundException("User", username);
        }
    }
}
