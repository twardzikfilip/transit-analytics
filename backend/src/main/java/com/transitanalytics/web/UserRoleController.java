package com.transitanalytics.web;

import com.transitanalytics.dto.UserRoleDto;
import com.transitanalytics.service.UserRoleService;
import org.springframework.web.bind.annotation.RequestMapping;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User roles (admin only)")
@RestController
@RequestMapping("/api/roles")
public class UserRoleController extends CrudController<UserRoleDto.Request, UserRoleDto.Response> {

    public UserRoleController(UserRoleService service) {
        super(service);
    }
}
