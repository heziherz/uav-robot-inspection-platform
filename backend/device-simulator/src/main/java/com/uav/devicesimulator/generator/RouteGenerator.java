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

    /**
     * 为指定设备生成“个性化路线”：
     *   - 起点相位错开：UAV-001 从第 0 点出发，UAV-002 从第 1 点……
     *   - 作业区域错开：每台设备把整条路线平移一点点（看起来分散在园区不同区域）
     *
     * @param latOffsetPerDevice 每台设备在纬度上的偏移量（度）
     * @param lngOffsetPerDevice 每台设备在经度上的偏移量（度）
     */
    public static RouteGenerator forDevice(double[][] baseRoute, String deviceNo,
                                           double latOffsetPerDevice, double lngOffsetPerDevice) {
        int seq = parseSeq(deviceNo);       // UAV-002 → 2
        double[][] shifted = shift(
                baseRoute,
                (seq - 1) * latOffsetPerDevice,
                (seq - 1) * lngOffsetPerDevice
        );
        return new RouteGenerator(shifted, seq - 1);
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

    /** 从设备编号提取序号：UAV-001 → 1，DOG-003 → 3 */
    private static int parseSeq(String deviceNo) {
        return Integer.parseInt(deviceNo.replaceAll("\\D+", ""));
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