package com.transitanalytics.web;

import com.transitanalytics.dto.UserRoleDto;
import com.transitanalytics.service.UserRoleService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/roles")
public class UserRoleController extends CrudController<UserRoleDto.Request, UserRoleDto.Response> {

    public UserRoleController(UserRoleService service) {
        super(service);
    }
}
