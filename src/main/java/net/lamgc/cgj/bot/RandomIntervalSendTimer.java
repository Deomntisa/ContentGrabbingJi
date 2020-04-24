package net.lamgc.cgj.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 随机间隔发送器
 */
public class RandomIntervalSendTimer extends TimerTask {

    private final static Timer timer = new Timer("Thread-RIST");
    private final static Logger log = LoggerFactory.getLogger("RandomIntervalSendTimer");
    private final static Map<Long, RandomIntervalSendTimer> timerMap = new HashMap<>();

    private final long timerId;
    private final Random timeRandom = new Random();
    private final AutoSender sender;
    private final long time;
    private final int floatTime;
    private AtomicBoolean loop = new AtomicBoolean();
    private final AtomicBoolean start = new AtomicBoolean();

    /**
     * 创建一个随机延迟发送器
     * @param timerId 该Timer的标识,
     *                标识必须是唯一的, 当使用相同id创建时, 将会返回该id所属的Timer对象
     * @param sender 自动发送器
     * @param time 最低时间(ms)
     * @param floatTime 浮动时间(ms)
     * @param startNow 现在开始
     * @param loop 是否循环
     */
    public static RandomIntervalSendTimer createTimer(long timerId, AutoSender sender, long time, int floatTime, boolean startNow, boolean loop) {
        if(timerMap.containsKey(timerId)) {
            return timerMap.get(timerId);
        }

        RandomIntervalSendTimer timer = new RandomIntervalSendTimer(timerId, sender, time, floatTime, startNow, loop);
        timerMap.put(timerId, timer);
        return timer;
    }

    /**
     * 通过Id获取Timer
     * @param id 待获取Timer对应的Id
     * @return 返回RandomIntervalSendTimer对象
     * @throws NoSuchElementException 当不存在Timer时抛出
     */
    public static RandomIntervalSendTimer getTimerById(long id) {
        if(!timerMap.containsKey(id)) {
            throw new NoSuchElementException("id=" + id);
        }
        return timerMap.get(id);
    }

    /**
     * 获取所有id
     * @return 所有TimerId的集合
     */
    public static Set<Long> timerIdSet() {
        return new HashSet<>(timerMap.keySet());
    }

    /**
     * 创建一个随机延迟发送器
     * @param timerId 该Timer的标识
     * @param sender 自动发送器
     * @param time 最低时间(ms)
     * @param floatTime 浮动时间(ms)
     * @param startNow 现在开始
     * @param loop 是否循环
     */
    private RandomIntervalSendTimer(long timerId, AutoSender sender, long time, int floatTime, boolean startNow, boolean loop) {
        this.timerId = timerId;
        this.sender = sender;
        this.time = time;
        this.floatTime = floatTime;
        timerMap.put(timerId, this);
        if(startNow) {
            start(loop);
        }
    }

    public void start() {
        start(this.loop.get());
    }

    private final static DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-ss HH:mm:ss");

    /**
     * 启动定时器
     * @param loop 是否循环, 如果为true, 则任务完成后会自动调用start方法继续循环, 直到被调用{@code #}或总定时器被销毁;
     */
    public void start(boolean loop) {
        this.loop.set(loop);
        long nextDelay = time + timeRandom.nextInt(floatTime);
        Date nextDate = new Date();
        nextDate.setTime(nextDate.getTime() + nextDelay);
        log.info("定时器 {} 下一延迟: {}ms ({})", Integer.toHexString(this.hashCode()), nextDelay, dateFormat.format(nextDate));
        if(start.get()) {
            try {
                Field state = this.getClass().getSuperclass().getDeclaredField("state");
                state.setAccessible(true);
                state.setInt(this, 0);
                state.setAccessible(false);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
                return;
            }
        }
        start.set(true);
        timer.schedule(this, nextDelay);
    }

    @Override
    public void run() {
        sender.send();
        if (this.loop.get()) {
            start();
        }
    }

    /**
     * 取消该定时器
     * @return 取消成功返回true
     */
    @Override
    public boolean cancel() {
        start.set(false);
        return super.cancel();
    }

    /**
     * 销毁这个定时器
     */
    public void destroy() {
        cancel();
        timerMap.remove(this.timerId);
    }

}
