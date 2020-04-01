package net.lamgc.cgj;

import net.lamgc.cgj.pixiv.PixivURL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class RankingUpdateTimer {

    private final Timer timer = new Timer("PixivRankingUpdate@" + Integer.toHexString(this.hashCode()), true);
    private final Logger log = LoggerFactory.getLogger("RankingUpdateTimer@" + Integer.toHexString(this.hashCode()));

    /**
     * 启动定时任务.
     * 本方法在设置后立即返回
     * @param firstRunDate 首次运行时间, 只需要设置日期, 时间为自动设置.
     */
    public void schedule(Date firstRunDate) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(firstRunDate == null ? new Date() : firstRunDate);
        LocalDate currentLocalDate = LocalDate.now();
        if(cal.get(Calendar.DAY_OF_YEAR) <= currentLocalDate.getDayOfYear() && cal.get(Calendar.HOUR_OF_DAY) >= 12) {
            cal.set(Calendar.DAY_OF_YEAR, cal.get(Calendar.DAY_OF_YEAR) + 1);
        }
        cal.set(Calendar.HOUR_OF_DAY, 12);
        cal.set(Calendar.MINUTE, 30);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        log.info("已设置排行榜定时更新, 首次运行时间: {}", cal.getTime());
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                now(null);
            }
        }, cal.getTime(), 86400000); // 1 Day
    }

    public void now(Date queryDate) {
        log.info("当前时间 {}, 定时任务开始执行...", new Date());
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(queryDate == null ? new Date() : queryDate);
        if(queryDate == null) {
            calendar.add(Calendar.DATE, -1);
        }
        log.info("正在获取 {} 期排行榜数据...", calendar.getTime());
        for (PixivURL.RankingMode rankingMode : PixivURL.RankingMode.values()) {
            for (PixivURL.RankingContentType contentType : PixivURL.RankingContentType.values()) {
                if(!contentType.isSupportedMode(rankingMode)) {
                    log.debug("不支持的类型, 填空值跳过...(类型: {}.{})", rankingMode.name(), contentType.name());
                }
                log.info("当前排行榜类型: {}.{}, 正在更新...", rankingMode.name(), contentType.name());
                try {
                    CQProcess.getRankingInfoByCache(contentType, rankingMode, calendar.getTime(), 1, 0);
                    log.info("排行榜 {}.{} 更新完成.", rankingMode.name(), contentType.name());
                } catch (IOException e) {
                    log.error("排行榜 {}.{} 更新时发生异常", rankingMode.name(), contentType.name());
                    log.error("异常信息如下", e);
                }
            }
        }
    }

    /**
     * 取消任务.
     */
    public void stop() {
        timer.cancel();
        log.warn("排行榜更新任务已取消.");
    }

}
