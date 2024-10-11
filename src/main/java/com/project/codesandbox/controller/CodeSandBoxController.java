package com.project.codesandbox.controller;

import cn.hutool.core.util.StrUtil;
import com.project.codesandbox.manager.DockerSandboxArgs;
import com.project.codesandbox.manager.DockerSandboxInteract;
import com.project.codesandbox.enums.ExecuteStatusEnum;
import com.project.codesandbox.enums.LanguageEnum;
import com.project.codesandbox.model.*;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * 实现代码沙箱接口
 */
@RequestMapping("/codesandbox")
@RestController
@CrossOrigin
public class CodeSandBoxController {

    /**
     * 通过传参实现
     */
    @Resource
    private DockerSandboxArgs dockerSandboxArgs;

    /**
     * 通过交互式输入
     */
    @Resource
    private DockerSandboxInteract dockerSandboxInteract;

    @PostMapping("/args")
    public ExecuteCodeResponse executeCodeByArgs(@RequestBody ExecuteCodeRequest executeCodeRequest){
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();
        List<String> inputList = executeCodeRequest.getInputList();
        if (StrUtil.isBlank(code)){
            return ExecuteCodeResponse.builder()
                    .outputList(null)
                    .status(1)
                    .message("代码不可为空！")
                    .judgeInfo(null)
                    .build();
        }
        LanguageEnum enumByValue = LanguageEnum.getEnumByValue(language);
        if (enumByValue == null){
            return ExecuteCodeResponse.builder()
                    .outputList(null)
                    .status(1)
                    .message("选择的语言不存在！")
                    .judgeInfo(null)
                    .build();
        }
        CodeExecuteRequest codeExecuteRequest = CodeExecuteRequest.builder()
                .input(inputList)
                .language(language)
                .code(code)
                .build();
        CodeExecuteResponse codeExecuteResponse = dockerSandboxArgs.execute(codeExecuteRequest);
        String executeStatus = codeExecuteResponse.getExecuteStatus();
        Long time = codeExecuteResponse.getTime();
        List<String> output = codeExecuteResponse.getOutput();
        Long memory = codeExecuteResponse.getMemory();
        String errorMessage = codeExecuteResponse.getErrorMessage();
        boolean success = codeExecuteResponse.isSuccess();
        ExecuteCodeResponse executeCodeResponse = ExecuteCodeResponse.builder().build();
        if (!success){
            if (executeStatus.equals(ExecuteStatusEnum.COMPILE_ERROR.getText())){
                executeCodeResponse.setStatus(0);
                executeCodeResponse.setMessage(errorMessage);
                return executeCodeResponse;
            }
            else{
                executeCodeResponse.setStatus(1);
                executeCodeResponse.setMessage(errorMessage);
                return executeCodeResponse;
            }
        }
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(ExecuteStatusEnum.SUCCESS.getText());
        judgeInfo.setTime(time);
        judgeInfo.setMemory(memory);
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setOutputList(output);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        executeCodeResponse.setMessage(ExecuteStatusEnum.SUCCESS.getText());
        return executeCodeResponse;
    }

    @PostMapping("/interact")
    public ExecuteCodeResponse executeCodeByInteract(@RequestBody ExecuteCodeRequest executeCodeRequest){
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();
        List<String> inputList = executeCodeRequest.getInputList();
        if (StrUtil.isBlank(code)){
            return ExecuteCodeResponse.builder()
                    .outputList(null)
                    .status(1)
                    .message("代码不可为空！")
                    .judgeInfo(null)
                    .build();
        }
        LanguageEnum enumByValue = LanguageEnum.getEnumByValue(language);
        if (enumByValue == null){
            return ExecuteCodeResponse.builder()
                    .outputList(null)
                    .status(1)
                    .message("选择的语言不存在！")
                    .judgeInfo(null)
                    .build();
        }
        CodeExecuteRequest codeExecuteRequest = CodeExecuteRequest.builder()
                .input(inputList)
                .language(language)
                .code(code)
                .build();
        CodeExecuteResponse codeExecuteResponse = dockerSandboxInteract.execute(codeExecuteRequest);
        String executeStatus = codeExecuteResponse.getExecuteStatus();
        Long time = codeExecuteResponse.getTime();
        List<String> output = codeExecuteResponse.getOutput();
        Long memory = codeExecuteResponse.getMemory();
        String errorMessage = codeExecuteResponse.getErrorMessage();
        boolean success = codeExecuteResponse.isSuccess();
        ExecuteCodeResponse executeCodeResponse = ExecuteCodeResponse.builder().build();
        if (!success){
            if (executeStatus.equals(ExecuteStatusEnum.COMPILE_ERROR.getText())){
                executeCodeResponse.setStatus(0);
                executeCodeResponse.setMessage(errorMessage);
                return executeCodeResponse;
            }
            else{
                executeCodeResponse.setStatus(1);
                executeCodeResponse.setMessage(errorMessage);
                return executeCodeResponse;
            }
        }
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(ExecuteStatusEnum.SUCCESS.getText());
        judgeInfo.setTime(time);
        judgeInfo.setMemory(memory);
        executeCodeResponse.setStatus(2);
        executeCodeResponse.setOutputList(output);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        executeCodeResponse.setMessage(ExecuteStatusEnum.SUCCESS.getText());
        return executeCodeResponse;
    }

    @GetMapping
    public String test(){
        return "hello";
    }

}
