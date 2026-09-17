package com.uav.devicesimulator.generator;

/**
 * 路线生成器：让设备沿预置路线“移动”。
 *
 * 解决两个问题：
 *   1. 多台设备不能挤在同一个点 → forDevice() 按设备序号错开“起点相位”和“作业区域”
 *   2. current() 应该返回“最近经过的点”，而不是“下一个要去的点”
 */
public class RouteGenerator {

    private final double[][] route;
    private int index;
    private double[] current;   // 最近一次经过的点

    public RouteGenerator(double[][] route, int startIndex) {
        if (route == null || route.length == 0) {
            throw new IllegalArgumentException("路线不能为空");
        }
        this.route = route;
        this.index = Math.floorMod(startIndex, route.length);
        this.current = route[this.index];
    }

    /** 网格列数：每 32 台设备换一行，避免偏移随设备序号无限增长 */
    private static final int GRID_COLUMNS = 32;

    /**
     * 为指定设备生成“个性化路线”：
     *   - 起点相位错开：UAV-001 从第 0 点出发，UAV-002 从第 1 点……
     *   - 作业区域错开：每台设备把整条路线平移一点点（看起来分散在园区不同区域）
     *
     * 平移量按「网格」而非「序号」计算：
     *   改造前是 (seq-1) * 偏移量，会随序号线性增长 ——
     *   1000 号设备的纬度偏移达到 999 × 0.0015° ≈ 166 km，
     *   设备会被撒到几百公里外，地图上根本看不到园区。
     *   改成网格后，序号 N 映射到 (N/32 行, N%32 列)，
     *   最大偏移恒定在 32 格以内（约 5 km，园区周边范围）。
     *   序号 1~32 仍落在第一行，与改造前的观感基本一致。
     *
     * @param latOffsetPerDevice 每行在纬度上的偏移量（度）
     * @param lngOffsetPerDevice 每列在经度上的偏移量（度）
     */
    public static RouteGenerator forDevice(double[][] baseRoute, String deviceNo,
                                           double latOffsetPerDevice, double lngOffsetPerDevice) {
        int idx = parseSeq(deviceNo) - 1;   // UAV-002 → 1
        int row = idx / GRID_COLUMNS;
        int col = idx % GRID_COLUMNS;
        double[][] shifted = shift(
                baseRoute,
                row * latOffsetPerDevice,
                col * lngOffsetPerDevice
        );
        return new RouteGenerator(shifted, idx);
    }

    /** 移动到下一个点并返回 */
    public double[] next() {
        current = route[index];
        index = (index + 1) % route.length;
        return current;
    }

    /** 当前所在点（最近一次经过的点，不移动） */
    public double[] current() {
        return current;
    }

    // ---------- 内部工具 ----------

    /**
     * 从设备编号提取序号：UAV-001 → 1，DOG-003 → 3。
     * 编号里没有数字时回退为 1，避免整批仿真因一个异常编号全部启动失败。
     */
    private static int parseSeq(String deviceNo) {
        String digits = deviceNo.replaceAll("\\D+", "");
        if (digits.isEmpty()) {
            return 1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 把整条路线平移 (latOff, lngOff)，并规整到 6 位小数（避免 double 浮点误差） */
    private static double[][] shift(double[][] base, double latOff, double lngOff) {
        double[][] result = new double[base.length][2];
        for (int i = 0; i < base.length; i++) {
            result[i][0] = round6(base[i][0] + latOff);
            result[i][1] = round6(base[i][1] + lngOff);
        }
        return result;
    }

    /** 保留 6 位小数（约 0.1 米精度） */
    private static double round6(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }
}