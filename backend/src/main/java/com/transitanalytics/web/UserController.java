package com.transitanalytics.web;

import com.transitanalytics.dto.UserDto;
import com.transitanalytics.service.UserService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController extends CrudController<UserDto.Request, UserDto.Response> {

    public UserController(UserService service) {
        super(service);
    }
}
