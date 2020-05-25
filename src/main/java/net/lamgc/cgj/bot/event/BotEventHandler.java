package net.lamgc.cgj.bot.event;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.lamgc.cgj.bot.BotAdminCommandProcess;
import net.lamgc.cgj.bot.BotCommandProcess;
import net.lamgc.cgj.bot.MessageEventExecutionDebugger;
import net.lamgc.cgj.bot.SettingProperties;
import net.lamgc.cgj.util.DateParser;
import net.lamgc.cgj.util.PagesQualityParser;
import net.lamgc.cgj.util.TimeLimitThreadPoolExecutor;
import net.lamgc.utils.base.runner.ArgumentsRunner;
import net.lamgc.utils.base.runner.ArgumentsRunnerConfig;
import net.lamgc.utils.base.runner.exception.DeveloperRunnerException;
import net.lamgc.utils.base.runner.exception.NoSuchCommandException;
import net.lamgc.utils.base.runner.exception.ParameterNoFoundException;
import net.lamgc.utils.event.*;
import net.lamgc.utils.event.EventObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.lang.reflect.Method;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BotEventHandler implements EventHandler {

    public final static String COMMAND_PREFIX = ".cgj";
    public final static String ADMIN_COMMAND_PREFIX = ".cgjadmin ";

    private final ArgumentsRunner processRunner;
    private final ArgumentsRunner adminRunner;

    private final static Logger log = LoggerFactory.getLogger(BotEventHandler.class.getName());

    private final static Map<Long, AtomicBoolean> muteStateMap = new Hashtable<>();

    /**
     * 所有缓存共用的JedisPool
     */
    private final static URI redisServerUri = URI.create("redis://" + System.getProperty("cgj.redisAddress"));
    public final static JedisPool redisServer = new JedisPool(redisServerUri.getHost(), redisServerUri.getPort() == -1 ? 6379 : redisServerUri.getPort());

    /**
     * 消息事件执行器
     */
    private final static EventExecutor executor = new EventExecutor(new TimeLimitThreadPoolExecutor(
            0,
            Math.max(Runtime.getRuntime().availableProcessors(), 4),
            Math.max(Math.max(Runtime.getRuntime().availableProcessors() * 2, 4), 32),
            30L,
    TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1536),
            new ThreadFactoryBuilder()
                    .setNameFormat("CommandProcess-%d")
                    .build()
    ));

    private static boolean initialled = false;
    static {
        initial();
    }

    public synchronized static void initial() {
        if(initialled) {
            Logger logger = LoggerFactory.getLogger("BotEventHandler@<init>");
            logger.warn("BotEventHandler已经执行过初始化方法, 可能存在多次执行的问题, 堆栈信息: \n {}",
                    Throwables.getStackTraceAsString(new Exception()));
            return;
        }

        executor.setEventUncaughtExceptionHandler(new EventUncaughtExceptionHandler() {
            private final Logger log = LoggerFactory.getLogger("EventUncaughtExceptionHandler");
            @Override
            public void exceptionHandler(Thread executeThread, EventHandler handler, Method handlerMethod, EventObject event, Throwable cause) {
                log.error("发生未捕获异常:\nThread:{}, EventHandler: {}, HandlerMethod: {}, EventObject: {}\n{}",
                        executeThread.getName(),
                        handler.toString(),
                        handlerMethod.getName(),
                        event.toString(),
                        Throwables.getStackTraceAsString(cause));
            }
        });
        try {
            executor.addHandler(new BotEventHandler());
        } catch (IllegalAccessException e) {
            LoggerFactory.getLogger("BotEventHandler@Static").error("添加Handler时发生异常", e);
        }
        initialled = true;
    }

    private final static AtomicBoolean preLoaded = new AtomicBoolean();
    /**
     * 预加载
     */
    public synchronized static void preLoad() {
        if(preLoaded.get()) {
            return;
        }
        try {
            BotAdminCommandProcess.loadPushList();
        } finally {
            preLoaded.set(true);
        }
    }

    private BotEventHandler() {
        ArgumentsRunnerConfig runnerConfig = new ArgumentsRunnerConfig();
        runnerConfig.setUseDefaultValueInsteadOfException(true);
        runnerConfig.setCommandIgnoreCase(true);
        runnerConfig.addStringParameterParser(new DateParser(new SimpleDateFormat("yyyy-MM-dd")));
        runnerConfig.addStringParameterParser(new PagesQualityParser());

        log.debug("DateParser添加情况: {}", runnerConfig.hasStringParameterParser(Date.class));

        processRunner = new ArgumentsRunner(BotCommandProcess.class, runnerConfig);
        adminRunner = new ArgumentsRunner(BotAdminCommandProcess.class, runnerConfig);

        BotCommandProcess.initialize();
    }

    /**
     * 投递消息事件
     * @param event 事件对象
     */
    @NotAccepted
    public static void executeMessageEvent(MessageEvent event) {
        String debuggerName = SettingProperties.getProperty(0, "debug.debugger");
        if(!event.getMessage().startsWith(ADMIN_COMMAND_PREFIX) &&
                !Strings.isNullOrEmpty(debuggerName)) {
            try {
                MessageEventExecutionDebugger debugger = MessageEventExecutionDebugger.valueOf(debuggerName.toUpperCase());
                debugger.debugger.accept(executor, event, SettingProperties.getProperties(SettingProperties.GLOBAL),
                                MessageEventExecutionDebugger.getDebuggerLogger(debugger));
            } catch(IllegalArgumentException e) {
                log.warn("未找到指定调试器: '{}'", debuggerName);
            } catch (Exception e) {
                log.error("事件调试处理时发生异常", e);
            }
        } else {
            BotEventHandler.executor.executor(event);
        }
    }

    /**
     * 以事件形式处理消息事件
     * @param event 消息事件对象
     */
    @SuppressWarnings("unused")
    public void processMessage(MessageEvent event) {
        String msg = event.getMessage();
        log.debug(event.toString());
        if(!match(msg)) {
            return;
        } else if(isMute(event.getFromGroup())) {
            log.debug("机器人已被禁言, 忽略请求.");
            return;
        }

        Pattern pattern = Pattern.compile("/\\s*(\".+?\"|[^:\\s])+((\\s*:\\s*(\".+?\"|[^\\s])+)|)|(\".+?\"|[^\"\\s])+");
        Matcher matcher = pattern.matcher(Strings.nullToEmpty(msg));
        List<String> argsList = new ArrayList<>();
        while (matcher.find()) {
            String arg = matcher.group();
            int startIndex = 0;
            int endIndex = arg.length();
            if(arg.startsWith("\"")) {
                while(arg.indexOf("\"", startIndex) == startIndex) {
                    startIndex++;
                }
            }

            if(arg.endsWith("\"")) {
                while(arg.charAt(endIndex - 1) == '\"') {
                    endIndex--;
                }
            }

            argsList.add(arg.substring(startIndex, endIndex));
        }
        String[] args = new String[argsList.size()];
        argsList.toArray(args);
        log.debug("传入参数: {}", Arrays.toString(args));
        argsList.add("-$fromGroup");
        argsList.add(String.valueOf(event.getFromGroup()));
        argsList.add("-$fromQQ");
        argsList.add(String.valueOf(event.getFromQQ()));
        args = Arrays.copyOf(args, args.length + 4);
        argsList.toArray(args);

        log.info("正在处理命令...");
        long time = System.currentTimeMillis();
        Object result;
        try {
            if(msg.toLowerCase().startsWith(ADMIN_COMMAND_PREFIX)) {
                if(!String.valueOf(event.getFromQQ())
                        .equals(SettingProperties.getProperty(0, "admin.adminId"))) {
                    result = "你没有执行该命令的权限！";
                } else {
                    result = adminRunner.run(args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length));
                }
            } else {
                result = processRunner.run(args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length));
            }
        } catch(NoSuchCommandException e) {
            result = "没有这个命令！请使用“.cgj”查看帮助说明！";
        } catch(ParameterNoFoundException e) {
            result = "命令缺少参数: " + e.getParameterName();
        } catch(DeveloperRunnerException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) {
                log.error("命令执行超时, 终止执行.");
                result = "色图姬发现这个命令的处理时间太久了！所以打断了这个命令。";
            } else if(cause instanceof NoSuchElementException && cause.getMessage().startsWith("No work found: ")) {
                String message = cause.getMessage();
                log.error("指定作品不存在.(Id: {})", message.substring(message.lastIndexOf(": ") + 2));
                result = "色图姬找不到这个作品！";
            } else {
                log.error("执行命令时发生异常", e);
                result = "色图姬在执行命令时遇到了一个错误！";
            }
        }
        long processTime = System.currentTimeMillis() - time;
        if(!Objects.isNull(result) && result instanceof String && !isMute(event.getFromGroup())) {
            try {
                event.sendMessage((String) result);
            } catch (Exception e) {
                log.error("发送消息时发生异常", e);
            }
        } else if(isMute(event.getFromGroup())) {
            log.warn("命令反馈时机器人已被禁言, 跳过反馈.");
        }
        long totalTime = System.currentTimeMillis() - time;
        log.info("命令反馈完成.(事件耗时: {}ms, P: {}%({}ms), R: {}%({}ms))", totalTime,
                String.format("%.3f", ((double) processTime / (double)totalTime) * 100F), processTime,
                String.format("%.3f", ((double) (totalTime - processTime) / (double)totalTime) * 100F), totalTime - processTime);
    }

    /**
     * 检查消息是否需要提交
     * @param message 要检查的消息
     * @return 如果为true则提交
     */
    public static boolean match(String message) {
        return message.startsWith(COMMAND_PREFIX) || message.startsWith(ADMIN_COMMAND_PREFIX);
    }

    private static boolean isMute(long groupId) {
        Boolean mute = isMute(groupId, false);
        return mute != null && mute;
    }

    /**
     * 查询某群是否被禁言.
     * @param groupId 群组Id
     * @param rawValue 是否返回原始值(当没有该群状态, 且本参数为true时, 将返回null)
     * @return 返回状态值, 如无该群禁言记录且rawValue = true, 则返回null
     */
    public static Boolean isMute(long groupId, boolean rawValue) {
        if(groupId <= 0) {
            return false;
        }
        AtomicBoolean state = muteStateMap.get(groupId);
        if(state == null && rawValue) {
            return null;
        }
        return state != null && state.get();
    }

    /**
     * 设置机器人禁言状态.
     * <p>设置该项可防止因机器人在禁言期间反馈请求导致被封号.</p>
     * @param mute 如果被禁言, 传入true
     */
    public static void setMuteState(long groupId, boolean mute) {
        if(!muteStateMap.containsKey(groupId)) {
            muteStateMap.put(groupId, new AtomicBoolean(mute));
        } else {
            muteStateMap.get(groupId).set(mute);
        }
        log.warn("群组 {} 机器人禁言状态已变更: {}", groupId, mute ? "已禁言" : "已解除");
    }

}
