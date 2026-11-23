package com.qianyuzhou.keepgen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;


/**
 * Health Connect 写入模块：把一次生成结果写进系统健康库（一条带 GPS 轨迹的跑步记录）。
 *
 * <h3>为什么整份实现都塞在内部类 {@link Impl} 里</h3>
 * 我们的 minSdk 是 21，而 Health Connect 的平台 API 是 API 34 才有的
 * （{@code android.health.connect.*}、{@code android.os.OutcomeReceiver} 甚至
 * {@code java.time.Instant} 在老系统上根本不存在）。
 * 如果把 {@code HealthConnectManager} / {@code Record} 这些类型直接摆在 {@code Hc} 的方法签名或字段上，
 * 老系统加载 {@code Hc} 做类校验时就得去解析这些类，轻则软失败、重则 NoClassDefFoundError。
 * 所以：<b>对外只有 Context / Generator.Result / Runnable / Consumer 这四种平台无关类型，
 * 所有 HC 类型与 java.time 全部关进 Impl，只有 SDK>=34 且探活通过才会被加载。</b>
 *
 * <h3>为什么所有失败都不弹窗</h3>
 * 国行华为 / 小米等无 GMS 机型基本没有 Health Connect（HC 在老机器上是
 * com.google.android.apps.healthdata 这个独立 APK，新机器才是系统模块）。
 * 这类设备上 {@code getSystemService} 直接返回 null。用户只是想生成运动数据，
 * 没义务理解「健康数据平台」是什么，所以：探不到就什么都不做，只往 prefs 落一行日志。
 *
 * <h3>单位与坐标系</h3>
 * HC 只认 WGS84。{@code Generator.Result.latlon} 本身就是 WGS84 逐点（见其字段注释），
 * 所以这里<b>不做</b> wgs2gcj / gcj2wgs 任何转换——转了反而把轨迹挪到几百米外。
 *
 * <h3>幂等</h3>
 * 每条记录都带 {@code Metadata.clientRecordId}。HC 对同一 clientRecordId 的行为是
 * 「同版本 = 幂等成功、更高版本 = 覆盖更新」，所以重复点一次生成不会写出一堆重复记录。
 */
public final class Hc {

    private Hc() {}   // 纯静态工具类

    /**
     * 失败/跳过回调。
     *
     * 为什么不用 java.util.function.Consumer：它在 API 24 才存在，
     * 我们 minSdk 21 —— 只要这个方法签名里出现 Consumer，老系统加载 Hc 这个类时
     * 就可能 NoClassDefFoundError，而那次加载跟「有没有 Health Connect」无关。
     * 用一个自带的接口，老系统上整个 Hc 都不会被加载，风险归零。
     */
    public interface Err { void onSkip(String why); }

    /** 日志落点。只有一个 key，只保留最后一次结果，不越写越大。 */
    private static final String PREF = "keepgen_hc";
    private static final String KEY_LAST = "last";

    // ---- 权限名 ------------------------------------------------------------
    // 注意单复数不一致，这是 HC 的历史遗留，不是笔误：
    //   写轨迹是单数 WRITE_EXERCISE_ROUTE，读轨迹才是复数 READ_EXERCISE_ROUTES。
    static final String P_WRITE_EXERCISE   = "android.permission.health.WRITE_EXERCISE";
    static final String P_WRITE_ROUTE      = "android.permission.health.WRITE_EXERCISE_ROUTE";
    static final String P_WRITE_DISTANCE   = "android.permission.health.WRITE_DISTANCE";
    static final String P_WRITE_CALORIES   = "android.permission.health.WRITE_TOTAL_CALORIES_BURNED";
    static final String P_WRITE_SPEED      = "android.permission.health.WRITE_SPEED";
    static final String P_WRITE_STEPS      = "android.permission.health.WRITE_STEPS";

    static final String[] WRITE_PERMS = {
            P_WRITE_EXERCISE, P_WRITE_ROUTE, P_WRITE_DISTANCE,
            P_WRITE_CALORIES, P_WRITE_SPEED, P_WRITE_STEPS,
    };

    /** 权限引导页要跳的系统页面。跳不过去再退到设置首页。 */
    static final String ACTION_HEALTH_HOME = "android.health.connect.action.HEALTH_HOME_SETTINGS";

    // =====================================================================
    // 对外接口（平台无关类型，老系统调用也安全）
    // =====================================================================

    /**
     * 探活：这台机器上到底有没有 Health Connect。
     *
     * <p>为什么先看 SDK_INT：{@code HealthConnectManager} 这个类在 API 34 以下是
     * 不存在于 classpath 的，直接摸它会炸。老系统一律 false，调用方据此静默跳过。
     *
     * <p>为什么 class 版取不到还要按名字再取一次：HC 是 mainline 模块，
     * {@code getSystemService(Class)} 依赖 SystemServiceRegistry 里的 class→name 映射；
     * 部分机型/ROM 只注册了名字没注册 class，只试 class 版会得到「有 HC 却探不到」的假阴性，
     * 假阴性在这套逻辑里等于永久不写。两条路都走一遍才有意义。
     */
    public static boolean available(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return false;
        try {
            return Impl.manager(ctx) != null;
        } catch (Throwable t) {
            log(ctx, "available:ERROR " + t);
            return false;
        }
    }

    /**
     * 询问「用户已经把写入权限都给了吗」。
     *
     * <p><b>为什么用 checkSelfPermission 而不是 HealthConnectManager.getGrantedPermissions()：</b>
     * API 34 的 platform 里 HealthConnectManager 只有 insertRecords / readRecords / aggregate /
     * deleteRecords / updateRecords / getChangeLogs 这些方法，
     * <b>根本没有 getGrantedPermissions</b>（那是不带 AAR 时唯一的授权查询 API 的 androidx 封装，
     * 见交付说明里的 javap 证据）。拿不到就按题目给的降级路线走：
     * 这里退成 checkSelfPermission，而真正的门闩放在 write() 里——
     * 写失败即视为无权限，绝不用这个查询结果去拦写。
     *
     * <p>所以本方法<b>只该用于界面提示</b>（要不要给用户一个「去授权」的入口），
     * 不要拿它当写不写的开关：它在个别机型上可能恒为 false（health.* 权限由 HC 模块定义，
     * 不同 ROM 的 PermissionManager 表现不一致），一旦误判就永远写不进去了。
     */
    public static boolean hasWritePermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return false;
        try {
            for (String p : WRITE_PERMS) {
                if (ctx.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 有 HC 但没给权限 = 值得给用户一个入口。界面层拿它决定要不要露「去开启」那一行。 */
    public static boolean shouldOfferPermission(Context ctx) {
        return available(ctx) && !hasWritePermission(ctx);
    }

    /**
     * 写一条带 GPS 轨迹的跑步记录。
     *
     * <p>回调契约（三条，调用方按这个写就行）：
     * <ul>
     *   <li>成功 → {@code onDone.run()}，并且已经落了一行 ok 日志；</li>
     *   <li>没有 HC / 参数不足 / 写入被拒 → {@code onError.accept(原因)}，
     *       原因只用于自己记日志，<b>不要拿去弹框</b>；</li>
     *   <li>任何情况下最多回调一次。回调线程不保证：跳过发生在调用线程，
     *       真正写入的结果在 HC 的 executor 线程。要碰 UI 就自己 post 到主线程，
     *       别在回调里假设「我一定在主线程」。</li>
     * </ul>
     *
     * <p>注意：本方法是异步的，且<b>不会</b>在写之前问权限。理由见 hasWritePermission 的注释——
     * 查询可能假阴性，而 HC 在无权限时的失败只是一次回调，没有任何系统弹窗，
     * 所以「直接写、失败再看」是这套机型环境下唯一不会永久卡死的策略。
     */
    public static void write(final Context ctx, final Generator.Result r,
                             final Runnable onDone, final Err onError) {
        if (ctx == null || r == null) {
            fail(ctx, onError, "null-arg");
            return;
        }
        if (!available(ctx)) {
            // 静默降级：不打扰用户，只留一行日志
            fail(ctx, onError, "skip:no-hc sdk=" + Build.VERSION.SDK_INT);
            return;
        }
        Impl.insert(ctx, r, onDone, onError);
    }

    /** 失败/跳过统一出口：日志一行 + 回调一次。绝不 toast、绝不 dialog。 */
    static void fail(Context ctx, Err onError, String why) {
        log(ctx, "skip " + why);
        if (onError != null) {
            try {
                onError.onSkip(why);
            } catch (Throwable ignore) {
                // 调用方自己的回调炸了不该反过来影响我们
            }
        }
    }

    /** 只留最后一次结果。写 prefs 也可能失败（存储满了、Context 是 null），所以整段吞掉。 */
    static void log(Context ctx, String line) {
        Log.i("keepgen-hc", line);
        if (ctx == null) return;
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_LAST, line).apply();
        } catch (Throwable ignore) {
        }
    }

    /** 给「设置」页/自己排查用：最后一次写入结果。 */
    public static String lastLog(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST, "");
        } catch (Throwable t) {
            return "";
        }
    }

    // =====================================================================
    // 以下全部只在 API>=34 且探活成功后才会被加载
    // =====================================================================

    static final class Impl {

        private static java.util.concurrent.ExecutorService exec;

        private static synchronized java.util.concurrent.Executor executor() {
            if (exec == null) {
                exec = java.util.concurrent.Executors.newSingleThreadExecutor(rr -> {
                    Thread t = new Thread(rr, "keepgen-hc");
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                });
            }
            return exec;
        }

        /** 取服务。class 版优先，名字版兜底（老 ROM 只注册名字，见 available() 注释）。 */
        static android.health.connect.HealthConnectManager manager(Context ctx) {
            android.health.connect.HealthConnectManager m = null;
            try {
                m = ctx.getSystemService(android.health.connect.HealthConnectManager.class);
            } catch (Throwable ignore) {
            }
            if (m == null) {
                try {
                    Object o = ctx.getSystemService(Context.HEALTHCONNECT_SERVICE);
                    if (o instanceof android.health.connect.HealthConnectManager) {
                        m = (android.health.connect.HealthConnectManager) o;
                    }
                } catch (Throwable ignore) {
                }
            }
            return m;
        }

        static void insert(final Context ctx, final Generator.Result r,
                           final Runnable onDone, final Err onError) {
            final android.health.connect.HealthConnectManager mgr;
            try {
                mgr = manager(ctx);
            } catch (Throwable t) {
                fail(ctx, onError, "mgr:ERROR " + t);
                return;
            }
            if (mgr == null) {
                fail(ctx, onError, "skip:no-hc mgr-null");
                return;
            }

            // 组记录可能因为数据形状（点数太少、时长<=0）抛异常，放在工作线程里组，
            // 好处是连组包失败都不会把主线程带崩。
            executor().execute(() -> {
                final java.util.List<android.health.connect.datatypes.Record> recs;
                try {
                    recs = build(ctx, r);
                } catch (Throwable t) {
                    fail(ctx, onError, "build:ERROR " + t);
                    return;
                }
                if (recs.isEmpty()) {
                    fail(ctx, onError, "skip:no-record");
                    return;
                }
                if (Build.VERSION.SDK_INT < 34) {   // 双保险：理论上到不了这里
                    fail(ctx, onError, "skip:race-sdk");
                    return;
                }
                try {
                    mgr.insertRecords(recs, executor(),
                            new android.os.OutcomeReceiver<
                                    android.health.connect.InsertRecordsResponse,
                                    android.health.connect.HealthConnectException>() {
                                @Override
                                public void onResult(android.health.connect.InsertRecordsResponse ok) {
                                    int n = (ok == null || ok.getRecords() == null) ? 0 : ok.getRecords().size();
                                    log(ctx, "ok inserted=" + n + " cid=keepgen-" + r.startMs);
                                    if (onDone != null) {
                                        try {
                                            onDone.run();
                                        } catch (Throwable ignore) {
                                        }
                                    }
                                }

                                @Override
                                public void onError(android.health.connect.HealthConnectException e) {
                                    // 无权限在这里表现为 ERROR_SECURITY。按题面约定：
                                    // 写失败即视为没有权限，静默吞掉。
                                    String code = (e == null) ? "null" : ("code=" + e.getErrorCode());
                                    fail(ctx, onError, "insert:FAIL " + code + " " + e);
                                }
                            });
                } catch (Throwable t) {
                    fail(ctx, onError, "insert:THROW " + t);
                }
            });
        }

        // ---- 组包 ----------------------------------------------------------

        static java.util.List<android.health.connect.datatypes.Record> build(
                Context ctx, Generator.Result r) {

            final java.util.List<android.health.connect.datatypes.Record> out = new java.util.ArrayList<>();

            // 时长兜底：durSec 是从起跑算到结束的秒数。HC 的 IntervalRecord 要求 end > start，
            // 极端情况下（用户把时长填成 0）会给一条 1 秒的记录而不是被拒。
            long start = r.startMs;
            long durMs = Math.round(r.durSec * 1000d);
            if (durMs <= 0) durMs = 1000L;
            long end = start + durMs;

            final java.time.Instant sInst = java.time.Instant.ofEpochMilli(start);
            final java.time.Instant eInst = java.time.Instant.ofEpochMilli(end);
            final java.time.ZoneOffset sOff = offset(start);
            final java.time.ZoneOffset eOff = offset(end);

            // 幂等键：同一次生成（startMs 相同）重复写，HC 会当成同一条记录覆盖，不会堆重复。
            // 每种 dataType 各自一个 id：HC 的 clientRecordId 在「同一 dataType 内」唯一，
            // 跨类型用同一个字符串虽然合法，但排查时看不出是哪条，加后缀更清楚。
            final String cid = "keepgen-" + start;
            final String cidD = cid + "-dist";
            final String cidC = cid + "-cal";
            final String cidS = cid + "-spd";
            final String cidSt = cid + "-steps";

            // ---- 轨迹 ----
            // ExerciseRoute 不是独立 Record，它只是 ExerciseSessionRecord 的一个字段（setRoute）。
            // 所以轨迹权限用 WRITE_EXERCISE_ROUTE，但写的时候并不单独插一条记录。
            final double[][] ll = r.latlon;   // WGS84 逐点 {lat, lon}，室内/无轨迹时为 null
            android.health.connect.datatypes.ExerciseRoute route = null;
            if (ll != null && ll.length >= 2) {
                java.util.List<android.health.connect.datatypes.ExerciseRoute.Location> locs =
                        new java.util.ArrayList<>(ll.length);
                // 时间轴用整段时长均分，而不是 i*dt：
                // dt 只保证「平均采样间隔」，首末点不一定正好落在 [startMs, end]。
                // HC 要求轨迹点时间落在 session 区间内且严格递增，均分是唯一稳的算法。
                double span = (double) (end - start);
                for (int i = 0; i < ll.length; i++) {
                    long t = start + Math.round(span * i / (ll.length - 1));
                    if (t > end) t = end;
                    // 构造参数顺序是 (time, latitude, longitude) —— 已用 javap 的 LocalVariableTable 核实，
                    // 顺序写反会把轨迹整个挪到地球另一边，这里绝不能凭记忆。
                    locs.add(new android.health.connect.datatypes.ExerciseRoute.Location.Builder(
                            java.time.Instant.ofEpochMilli(t), ll[i][0], ll[i][1]).build());
                }
                route = new android.health.connect.datatypes.ExerciseRoute(locs);
            }

            // ---- 1) 运动会话（跑道类型：跑步） ----
            // 平台常量叫 ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING（=33），
            // 不是 ExerciseSessionRecord.EXERCISE_TYPE_RUNNING —— 后者在 platform API 里不存在。
            android.health.connect.datatypes.ExerciseSessionRecord.Builder sb =
                    new android.health.connect.datatypes.ExerciseSessionRecord.Builder(
                            meta(cid), sInst, eInst,
                            android.health.connect.datatypes.ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING);
            sb.setStartZoneOffset(sOff);
            sb.setEndZoneOffset(eOff);
            if (route != null) sb.setRoute(route);
            out.add(sb.build());

            // ---- 2) 距离 ----
            if (r.distM > 0) {
                android.health.connect.datatypes.IntervalRecord ir =
                        new android.health.connect.datatypes.DistanceRecord.Builder(
                                meta(cidD), sInst, eInst,
                                android.health.connect.datatypes.units.Length.fromMeters(r.distM))
                                .setStartZoneOffset(sOff).setEndZoneOffset(eOff).build();
                out.add((android.health.connect.datatypes.Record) ir);
            }

            // ---- 3) 总消耗 ----
            if (r.calories > 0) {
                android.health.connect.datatypes.IntervalRecord ir =
                        new android.health.connect.datatypes.TotalCaloriesBurnedRecord.Builder(
                                meta(cidC), sInst, eInst,
                                android.health.connect.datatypes.units.Energy.fromCalories(r.calories))
                                .setStartZoneOffset(sOff).setEndZoneOffset(eOff).build();
                out.add((android.health.connect.datatypes.Record) ir);
            }

            // ---- 4) 速度（逐点采样） ----
            // 平台类名是 SpeedRecordSample，不是 Sample；没有 Builder，直接 new (Velocity, Instant)。
            java.util.List<android.health.connect.datatypes.SpeedRecord.SpeedRecordSample> samples =
                    new java.util.ArrayList<>();
            if (ll != null && ll.length >= 2 && r.dt > 0) {
                double span = (double) (end - start);
                for (int i = 0; i < ll.length - 1; i++) {
                    double d = Geo.distM(ll[i][0], ll[i][1], ll[i + 1][0], ll[i + 1][1]);
                    long t = start + Math.round(span * i / (ll.length - 1));
                    samples.add(new android.health.connect.datatypes.SpeedRecord.SpeedRecordSample(
                            android.health.connect.datatypes.units.Velocity.fromMetersPerSecond(d / r.dt),
                            java.time.Instant.ofEpochMilli(t)));
                }
            } else if (r.durSec > 0 && r.distM > 0) {
                // 无轨迹（室内跑步机 / 场地绕圈）也要给速度：用整段平均值填一个采样点。
                samples.add(new android.health.connect.datatypes.SpeedRecord.SpeedRecordSample(
                        android.health.connect.datatypes.units.Velocity.fromMetersPerSecond(r.distM / r.durSec),
                        sInst));
            }
            if (!samples.isEmpty()) {
                android.health.connect.datatypes.IntervalRecord ir =
                        new android.health.connect.datatypes.SpeedRecord.Builder(
                                meta(cidS), sInst, eInst, samples)
                                .setStartZoneOffset(sOff).setEndZoneOffset(eOff).build();
                out.add((android.health.connect.datatypes.Record) ir);
            }

            // ---- 5) 步数 ----
            // stepsCum 是逐点累计值，末位才是总数；拿平均步频反推会差出几十步。
            long steps = 0;
            if (r.stepsCum != null && r.stepsCum.length > 0) {
                steps = r.stepsCum[r.stepsCum.length - 1];
            } else if (r.totalSteps > 0) {
                steps = Math.round(r.totalSteps);
            }
            if (steps > 0) {
                android.health.connect.datatypes.IntervalRecord ir =
                        new android.health.connect.datatypes.StepsRecord.Builder(
                                meta(cidSt), sInst, eInst, steps)
                                .setStartZoneOffset(sOff).setEndZoneOffset(eOff).build();
                out.add((android.health.connect.datatypes.Record) ir);
            }

            return out;
        }

        /** 每条记录一份 Metadata：clientRecordId 是幂等键，记录方式写「手工录入」（数据是生成的，不是传感器采的）。 */
        static android.health.connect.datatypes.Metadata meta(String cid) {
            return new android.health.connect.datatypes.Metadata.Builder()
                    .setClientRecordId(cid)
                    .setClientRecordVersion(1L)
                    .setRecordingMethod(android.health.connect.datatypes.Metadata.RECORDING_METHOD_MANUAL_ENTRY)
                    .setDevice(new android.health.connect.datatypes.Device.Builder()
                            .setManufacturer("keepgen")
                            .setModel("generator")
                            .setType(android.health.connect.datatypes.Device.DEVICE_TYPE_UNKNOWN)
                            .build())
                    .build();
        }

        /** 该时刻本机时区偏移。HC 用它做「本地时间」展示，缺了会在跨时区/夏令时上显示歪。 */
        static java.time.ZoneOffset offset(long ms) {
            try {
                return java.time.ZoneId.systemDefault().getRules()
                        .getOffset(java.time.Instant.ofEpochMilli(ms));
            } catch (Throwable t) {
                return java.time.ZoneOffset.UTC;
            }
        }
    }
}