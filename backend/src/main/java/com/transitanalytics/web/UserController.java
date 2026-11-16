package com.transitanalytics.web;

import com.transitanalytics.dto.UserDto;
import com.transitanalytics.service.UserService;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users (admin only)")
@RestController
@RequestMapping("/api/users")
public class UserController extends CrudController<UserDto.Request, UserDto.Response> {

    public UserController(UserService service) {
        super(service);
    }
}
