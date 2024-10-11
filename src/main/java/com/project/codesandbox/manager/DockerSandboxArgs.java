package com.project.codesandbox.manager;

import java.io.*;
import java.util.Collections;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DockerClientBuilder;
import com.project.codesandbox.enums.ExecuteStatusEnum;
import com.project.codesandbox.enums.LanguageEnum;
import com.project.codesandbox.model.CodeExecuteRequest;
import com.project.codesandbox.model.CodeExecuteResponse;
import com.project.codesandbox.model.ExecuteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱
 */
@Slf4j
@Component
public class DockerSandboxArgs {

    private static final DockerClient DOCKER_CLIENT = DockerClientBuilder.getInstance().build();

    private static String image = "codesandbox1:latest";

    /**
     * 内存限制，单位为字节，默认为 1024 * 1024 * 60 MB
     */
    private static long memoryLimit = 1024 * 1024 * 128;

    private static long memorySwap = 0;

    /**
     * 最大可消耗的 cpu 数
     */
    private static long cpuCount = 1;

    private static long timeoutLimit = 3;

    private static TimeUnit timeUnit = TimeUnit.SECONDS;

    private static StopWatch stopWatch = new StopWatch();
    /**
     * 执行代码
     *
     * @param codeExecuteRequest
     * @return {@link CodeExecuteResponse}
     */
    public CodeExecuteResponse execute(CodeExecuteRequest codeExecuteRequest) {

        // 写入文件
        String userDir = System.getProperty("user.dir");
        String language = codeExecuteRequest.getLanguage();
        String code = codeExecuteRequest.getCode();
        LanguageEnum languageCmdEnum = LanguageEnum.getEnumByValue(language);

        if (languageCmdEnum == null) {
            log.info("不支持的编程语言");
            return CodeExecuteResponse.builder().success(false).executeStatus(ExecuteStatusEnum.LANGUAGE_ERROR.getText()).build();
        }

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
        String containerId = createContainer(userCodePath);

        // 编译代码
        String[] compileCmd = languageCmdEnum.getCompileCmd();
        CodeExecuteResponse codeExecuteResponse = CodeExecuteResponse.builder().build();
        ExecuteResult executeResult;
        // 不为空则代表需要编译
        if (compileCmd != null) {
            executeResult = execCmd(containerId, null, compileCmd, 0);

            log.info("编译完成...");
            // 编译错误
            if (!executeResult.isSuccess()) {
                // 清理文件和容器
                cleanFileAndContainer(userCodeParentPath, containerId);
                codeExecuteResponse.setExecuteStatus(ExecuteStatusEnum.COMPILE_ERROR.getText());
                return codeExecuteResponse;
            }
        }

        // 执行代码
        List<String> inputList = codeExecuteRequest.getInput();
        if (CollUtil.isEmpty(inputList)){
            executeResult = execCmd(containerId, null, languageCmdEnum.getRunCmd(), 1);
            if (!executeResult.isSuccess()) {
                // 清理文件和容器
                cleanFileAndContainer(userCodeParentPath, containerId);
                codeExecuteResponse.setExecuteStatus(ExecuteStatusEnum.RUNTIME_ERROR.getText());
                codeExecuteResponse.setSuccess(false);
                codeExecuteResponse.setErrorMessage(executeResult.getErrorMessage());
                return codeExecuteResponse;
            }
            codeExecuteResponse.setExecuteStatus(ExecuteStatusEnum.SUCCESS.getText());
            codeExecuteResponse.setSuccess(true);
            codeExecuteResponse.setTime(executeResult.getTime());
            codeExecuteResponse.setMemory(executeResult.getMemory());
            codeExecuteResponse.setOutput(Collections.singletonList(executeResult.getOutput()));
            cleanFileAndContainer(userCodeParentPath, containerId);
            return codeExecuteResponse;
        }

        List<String> outputList = new ArrayList<>();
        long time = 0;
        long memory = 0;
        for (String input : inputList) {
            executeResult = execCmd(containerId, input, languageCmdEnum.getRunCmd(), 1);
            if (!executeResult.isSuccess()) {
                // 清理文件和容器
                cleanFileAndContainer(userCodeParentPath, containerId);
                codeExecuteResponse.setExecuteStatus(ExecuteStatusEnum.RUNTIME_ERROR.getText());
                codeExecuteResponse.setErrorMessage(executeResult.getErrorMessage());
                codeExecuteResponse.setSuccess(false);
                codeExecuteResponse.setOutput(outputList);
                return codeExecuteResponse;
            }
            outputList.add(executeResult.getOutput());
            time = Math.max(time, executeResult.getTime());
            memory = Math.max(memory, executeResult.getMemory());
        }
        codeExecuteResponse.setExecuteStatus(ExecuteStatusEnum.SUCCESS.getText());
        codeExecuteResponse.setSuccess(true);
        codeExecuteResponse.setTime(time);
        codeExecuteResponse.setMemory(memory);
        codeExecuteResponse.setOutput(outputList);
        // 清理文件和容器
        cleanFileAndContainer(userCodeParentPath, containerId);
        return codeExecuteResponse;
    }

    /**
     * 清理文件和容器
     *
     * @param userCodePath 用户代码路径
     * @param containerId  容器 ID
     */
    private void cleanFileAndContainer(String userCodePath, String containerId) {
        // 清理临时目录
        FileUtil.del(userCodePath);

        // 关闭并删除容器
        DOCKER_CLIENT.stopContainerCmd(containerId).exec();
        DOCKER_CLIENT.removeContainerCmd(containerId).exec();
    }

    /**
     * 执行命令
     *
     * @param containerId 容器 ID
     * @param cmd         CMD
     * @return {@link CodeExecuteResponse}
     */
    private ExecuteResult execCmd(String containerId, String input, String[] cmd, int flag) {
        // 正常返回信息
        ByteArrayOutputStream resultStream = new ByteArrayOutputStream();
        // 错误信息
        ByteArrayOutputStream errorResultStream = new ByteArrayOutputStream();

        // 结果
        final boolean[] result = {true};
        final boolean[] timeout = { true };
        final long[] maxMemory = {0};
        try (ResultCallback.Adapter<Frame> frameAdapter = new ResultCallback.Adapter<Frame>() {

            @Override
            public void onComplete() {
                // 是否超时
                timeout[0] = false;
                super.onComplete();
            }


            @Override
            public void onNext(Frame frame) {
                StreamType streamType = frame.getStreamType();
                byte[] payload = frame.getPayload();
                if (StreamType.STDERR.equals(streamType)) {
                    try {
                        result[0] = false;
                        errorResultStream.write(payload);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    try {
                        result[0] = true;
                        resultStream.write(payload);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                super.onNext(frame);
            }
        }) {
            if (flag == 1){
                if (StrUtil.isNotBlank(input)) {
                    String[] inputArgsArray = input.split(" ");
                    cmd = ArrayUtil.append(cmd, inputArgsArray);
                }
            }
            ExecCreateCmdResponse execCompileCmdResponse = DOCKER_CLIENT.execCreateCmd(containerId)
                    .withCmd(cmd)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();

            // 获取占用的内存
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
            // 通过输入流传递参数

            String execId = execCompileCmdResponse.getId();
            stopWatch.start();
            DOCKER_CLIENT.execStartCmd(execId)
                    .exec(frameAdapter).awaitCompletion(timeoutLimit, timeUnit);
            stopWatch.stop();
            long time = stopWatch.getLastTaskTimeMillis() / 1000;

            if (timeout[0]) {
                return ExecuteResult
                        .builder()
                        .success(false)
                        .errorMessage("执行超时")
                        .executeStatus(ExecuteStatusEnum.TIMEOUT.getText())
                        .build();
            }

            return ExecuteResult
                    .builder()
                    .success(result[0])
                    .output(resultStream.toString())
                    .errorMessage(errorResultStream.toString())
                    .time(time)
                    .memory(maxMemory[0] / (1024 * 1024))
                    .build();

        } catch (IOException | InterruptedException e) {
            log.info(e.getMessage());
            return ExecuteResult
                    .builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private ExecuteResult execCmdTest(String containerId, String input, String[] cmd, int flag) {
        // 正常返回信息
        ByteArrayOutputStream resultStream = new ByteArrayOutputStream();
        // 错误信息
        ByteArrayOutputStream errorResultStream = new ByteArrayOutputStream();

        // 结果
        final boolean[] result = {true};
        final boolean[] timeout = { true };
        final long[] maxMemory = {0};
        try (ResultCallback.Adapter<Frame> frameAdapter = new ResultCallback.Adapter<Frame>() {

            @Override
            public void onComplete() {
                // 是否超时
                timeout[0] = false;
                super.onComplete();
            }


            @Override
            public void onNext(Frame frame) {
                StreamType streamType = frame.getStreamType();
                byte[] payload = frame.getPayload();
                if (StreamType.STDERR.equals(streamType)) {
                    try {
                        result[0] = false;
                        errorResultStream.write(payload);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    try {
                        result[0] = true;
                        resultStream.write(payload);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                super.onNext(frame);
            }
        }) {
            ExecCreateCmdResponse execCompileCmdResponse = DOCKER_CLIENT.execCreateCmd(containerId)
                    .withCmd(cmd)
                    .withAttachStderr(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .exec();

            if (flag == 1){
                if (StrUtil.isNotBlank(input)) {
                    String inputArgsArray = input.replace(" ","\n")+"\n";
                    DOCKER_CLIENT.execCreateCmd(containerId)
                            .withCmd(inputArgsArray)
                            .exec();
                }
            }
            // 获取占用的内存
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
            // 通过输入流传递参数
            String execId = execCompileCmdResponse.getId();
            stopWatch.start();
            DOCKER_CLIENT.execStartCmd(execId)
                    .exec(frameAdapter).awaitCompletion(timeoutLimit, timeUnit);
            stopWatch.stop();
            long time = stopWatch.getLastTaskTimeMillis() / 1000;

            if (timeout[0]) {
                return ExecuteResult
                        .builder()
                        .success(false)
                        .errorMessage("执行超时")
                        .executeStatus(ExecuteStatusEnum.TIMEOUT.getText())
                        .build();
            }

            return ExecuteResult
                    .builder()
                    .success(result[0])
                    .output(resultStream.toString())
                    .errorMessage(errorResultStream.toString())
                    .time(time)
                    .memory(maxMemory[0] / (1024 * 1024))
                    .build();

        } catch (IOException | InterruptedException e) {
            log.info(e.getMessage());
            return ExecuteResult
                    .builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * 创建容器
     *
     * @return {@link String}
     */
    private String createContainer(String codeFile) {
        CreateContainerCmd containerCmd = DOCKER_CLIENT.createContainerCmd(image);
        HostConfig hostConfig = new HostConfig();
        hostConfig.withMemory(memoryLimit);
        hostConfig.withMemorySwap(memorySwap);
        hostConfig.withCpuCount(cpuCount);

        CreateContainerResponse createContainerResponse = containerCmd
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withAttachStdin(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(true)
                .exec();
        // 启动容器
        String containerId = createContainerResponse.getId();
        DOCKER_CLIENT.startContainerCmd(containerId).exec();

        // 将代码复制到容器中
        DOCKER_CLIENT.copyArchiveToContainerCmd(containerId)
                .withHostResource(codeFile)
                .withRemotePath("/box")
                .exec();
        return containerId;
    }
}
