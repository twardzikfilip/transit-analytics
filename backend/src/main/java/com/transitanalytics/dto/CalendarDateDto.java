package com.transitanalytics.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public final class CalendarDateDto {

    private CalendarDateDto() {
    }

    /** @param exceptionType 1 = service added on this date, 2 = service removed */
    public record Request(
            @NotBlank @Size(max = 64) String serviceId,
            @NotNull LocalDate date,
            @NotNull @Min(1) @Max(2) Short exceptionType) {
    }

    public record Response(Long id, String serviceId, LocalDate date, Short exceptionType) {
    }

    public record Filter(String serviceId) {
    }
}
