package com.project.codesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteResult {

    private String output;

    private boolean success;

    private String executeStatus;

    private String errorMessage;

    private Long memory;

    private Long time;

}
