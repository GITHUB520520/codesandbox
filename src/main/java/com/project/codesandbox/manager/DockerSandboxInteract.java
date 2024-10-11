package com.project.codesandbox.manager;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.DockerClientBuilder;
import com.project.codesandbox.enums.ExecuteStatusEnum;
import com.project.codesandbox.enums.LanguageEnum;
import com.project.codesandbox.model.CodeExecuteRequest;
import com.project.codesandbox.model.CodeExecuteResponse;
import com.project.codesandbox.model.ExecuteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Component
public class DockerSandboxInteract {

    private static final String IMAGE = "codesandbox1:latest";
    private static final long TIMEOUT_LIMIT = 3;
    private static final TimeUnit TIME_UNIT = TimeUnit.SECONDS;
    private static final DockerClient DOCKER_CLIENT = DockerClientBuilder.getInstance().build();
    // 资源限制：1 CPU 核心，128MB 内存
    private static final String CPU_LIMIT = "1.0"; // 限制使用一个 CPU 核心
    private static final String MEMORY_LIMIT = "128m"; // 限制内存为 128MB

    public CodeExecuteResponse execute(CodeExecuteRequest codeExecuteRequest) {
        String language = codeExecuteRequest.getLanguage();
        String code = codeExecuteRequest.getCode();
        LanguageEnum languageCmdEnum = LanguageEnum.getEnumByValue(language);

        if (languageCmdEnum == null) {
            return errorResponse(ExecuteStatusEnum.LANGUAGE_ERROR.getText(), "不支持的编程语言");
        }

        // 准备用户代码
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + "tempCode" + File.separator + language;
        // 判断全局代码目录是否存在，没有则新建
        File globalCodePath = new File(globalCodePathName);
        if (!globalCodePath.exists()) {
            boolean mkdir = globalCodePath.mkdirs();
            if (!mkdir) {
                log.info("创建全局代码目录失败");
            }
        }

        // 把用户的代码隔离存放
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + languageCmdEnum.getSaveFileName();
        FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
        String containerId = null;

        try {
            // 创建容器
            containerId = createContainer(userCodePath, languageCmdEnum.getSaveFileName());

            // 编译阶段
            ExecuteResult compileResult = compileCode(containerId, languageCmdEnum.getCompileCmd());
            if (!compileResult.isSuccess()) {
                cleanUp(containerId, userCodeParentPath);
                return errorResponse(ExecuteStatusEnum.COMPILE_ERROR.getText(), compileResult.getErrorMessage());
            }
            log.info("编译成功！");
            // 运行阶段
            CodeExecuteResponse runResponse = runCode(containerId, codeExecuteRequest, languageCmdEnum);
            cleanUp(containerId, userCodeParentPath);
            return runResponse;

        } catch (IOException | InterruptedException | ExecutionException e) {
            if (containerId != null) {
                try {
                    cleanUp(containerId, userCodeParentPath);
                } catch (Exception ex) {
                    log.error("清理容器时出错: {}", ex.getMessage());
                }
            }
            return errorResponse(ExecuteStatusEnum.RUNTIME_ERROR.getText(), e.getMessage());
        }
    }

    private CodeExecuteResponse runCode(String containerId, CodeExecuteRequest codeExecuteRequest, LanguageEnum languageCmdEnum) throws IOException, InterruptedException, ExecutionException {
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;
        long maxMemory = 0;
        List<String> inputList = codeExecuteRequest.getInput();
        ExecuteResult executeResult = null;
        if (CollUtil.isEmpty(inputList)){
            executeResult = execCmd(containerId, languageCmdEnum.getRunCmd(), true, null);
            if (!executeResult.isSuccess()) {
                return errorResponse(ExecuteStatusEnum.RUNTIME_ERROR.getText(), executeResult.getErrorMessage());
            }
            return successResponse(Collections.singletonList(executeResult.getOutput()), executeResult.getTime(), executeResult.getMemory());
        }
        for (String input : codeExecuteRequest.getInput()) {
            executeResult = execCmd(containerId, languageCmdEnum.getRunCmd(), true, input);
            if (!executeResult.isSuccess()) {
                return errorResponse(ExecuteStatusEnum.RUNTIME_ERROR.getText(), executeResult.getErrorMessage());
            }
            outputList.add(executeResult.getOutput());
            maxTime = Math.max(maxTime, executeResult.getTime());
            maxMemory = Math.max(maxMemory, executeResult.getMemory());
        }
        return successResponse(outputList, maxTime, maxMemory);
    }


    private ExecuteResult compileCode(String containerId, String[] compileCmd) throws IOException, InterruptedException {
        // 使用 ProcessBuilder 执行编译命令
        ProcessBuilder processBuilder = new ProcessBuilder();
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("exec");
        command.add(containerId);
        command.addAll(Arrays.asList(compileCmd));
        processBuilder.command(command);
        processBuilder.redirectErrorStream(true); // 将错误流合并到标准输出

        // 启动编译进程
        Process process = processBuilder.start();

        // 读取编译过程中的输出
        String output = readStream(process.getInputStream());
        int exitCode = process.waitFor(); // 等待进程完成并获取退出代码

        // 返回执行结果
        return ExecuteResult.builder()
                .success(exitCode == 0) // 如果退出代码为 0，则编译成功
                .output(output) // 返回编译输出
                .errorMessage(exitCode != 0 ? "编译失败" : "") // 如果编译失败，返回错误信息
                .build();
    }

    private ExecuteResult execCmd(String containerId, String[] cmd, boolean withInput, String input) throws IOException, InterruptedException, ExecutionException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("exec");
        command.add("-i");
        command.add(containerId);
        command.addAll(Arrays.asList(cmd));
        final long[] maxMemory = {0};
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        // 启动进程
        Process process = processBuilder.start();
        StatsCmd statsCmd = DOCKER_CLIENT.statsCmd(containerId);
        ResultCallback<Statistics> statisticsResultCallback = statsCmd.exec(new ResultCallback<Statistics>() {

            @Override
            public void onNext(Statistics statistics) {
                maxMemory[0] = Math.max(statistics.getMemoryStats().getUsage(), maxMemory[0]);
            }

            @Override
            public void close() throws IOException {

            }

            @Override
            public void onStart(Closeable closeable) {

            }

            @Override
            public void onError(Throwable throwable) {

            }

            @Override
            public void onComplete() {

            }
        });
        statsCmd.exec(statisticsResultCallback);
        // 开始监控资源使用
        long startTime = System.nanoTime();

        // 如果需要，提供输入
        if (withInput && input != null) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                writer.write(input + "\n");
                writer.flush();
            }
        }

        boolean completed = process.waitFor(TIMEOUT_LIMIT, TIME_UNIT);
        long endTime = System.nanoTime();
        long duration = TimeUnit.NANOSECONDS.toSeconds(endTime - startTime);

        if (!completed) {
            process.destroy();
            return ExecuteResult.builder()
                    .success(false)
                    .errorMessage("执行超时")
                    .executeStatus(ExecuteStatusEnum.TIMEOUT.getText())
                    .time(duration)
                    .memory(maxMemory[0] / (1024 * 1024))
                    .build();
        }

        String output = readStream(process.getInputStream());
        String errorOutput = readStream(process.getErrorStream()); // 已经重定向错误流

        return ExecuteResult.builder()
                .success(process.exitValue() == 0)
                .output(output)
                .errorMessage(errorOutput)
                .time(duration)
                .memory(maxMemory[0] / (1024 * 1024))
                .build();
    }


    private long parseMemoryUsage(String memUsage) {
        // memUsage 格式: "10.45MiB / 1.944GiB"
        String[] parts = memUsage.split("/");
        String usedMem = parts[0].trim();
        return convertToBytes(usedMem);
    }

    private long convertToBytes(String memStr) {
        try {
            if (memStr.endsWith("KiB")) {
                return (long) (Double.parseDouble(memStr.replace("KiB", "").trim()) * 1024);
            } else if (memStr.endsWith("MiB")) {
                return (long) (Double.parseDouble(memStr.replace("MiB", "").trim()) * 1024 * 1024);
            } else if (memStr.endsWith("GiB")) {
                return (long) (Double.parseDouble(memStr.replace("GiB", "").trim()) * 1024 * 1024 * 1024);
            } else if (memStr.endsWith("KiB")) {
                return Long.parseLong(memStr.replace("KiB", "").trim());
            } else {
                return 0;
            }
        } catch (NumberFormatException e) {
            log.error("解析内存使用时出错: {}", e.getMessage());
            return 0;
        }
    }

    private String readStream(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append(System.lineSeparator());
        }
        return output.toString().trim();
    }

    private String createContainer(String codeFile, String codeFileName) throws IOException, InterruptedException {
        String containerId = UUID.randomUUID().toString();
        // 创建带有资源限制的 Docker 容器 docker run -dit --name 1 --memory 128m --cpus 1.0 -v /home/hrl/code/src/main/resources/languageCode/Main.java:/box codesandbox:latest /bin/bash
        ProcessBuilder processBuilder = new ProcessBuilder("docker", "run", "-dit", "--name", containerId,
                "--memory", MEMORY_LIMIT, "--cpus", CPU_LIMIT, "-v", codeFile + ":/box/" + codeFileName, IMAGE, "/bin/bash");
        Process process = processBuilder.start();
        process.waitFor();
        if (process.exitValue() != 0){
            log.info("创建容器失败！");

        }
        return containerId;
    }

    private void cleanUp(String containerId, String userCodePath) throws IOException, InterruptedException {
        // 删除容器
        FileUtil.del(userCodePath);

        // 关闭并删除容器
        DOCKER_CLIENT.stopContainerCmd(containerId).exec();
        DOCKER_CLIENT.removeContainerCmd(containerId).exec();
    }

    private CodeExecuteResponse errorResponse(String status, String errorMsg) {
        return CodeExecuteResponse.builder()
                .success(false)
                .executeStatus(status)
                .errorMessage(errorMsg)
                .build();
    }

    private CodeExecuteResponse successResponse(List<String> output, long time, long memory) {
        return CodeExecuteResponse.builder()
                .success(true)
                .executeStatus(ExecuteStatusEnum.SUCCESS.getText())
                .time(time)
                .memory(memory / (1024 * 1024)) // 转换为 MB
                .output(output)
                .build();
    }
}

