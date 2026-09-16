import { useEffect, useRef, useState } from 'react'
import { Card, Row, Col, Statistic, Spin } from 'antd'
import axios from 'axios'
import * as echarts from 'echarts'

/**
 * 通用 ECharts 容器：
 *   用 useEffect 管理图表实例（init / setOption / dispose），不依赖第三方 React 封装，
 *   避免与 React 19 的版本兼容问题。
 */
function Chart({ option, height = 300 }) {
  const domRef = useRef(null)
  const chartRef = useRef(null)

  useEffect(() => {
    if (!domRef.current) return
    if (!chartRef.current) {
      chartRef.current = echarts.init(domRef.current)
    }
    chartRef.current.setOption(option, true)
  }, [option])

  useEffect(() => () => chartRef.current?.dispose(), [])   // 卸载时销毁实例

  return <div ref={domRef} style={{ width: '100%', height }} />
}

export default function StatsDashboard() {
  const [overview, setOverview] = useState({})
  const [types, setTypes] = useState([])
  const [levels, setLevels] = useState([])
  const [trend, setTrend] = useState([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.all([
      axios.get('/api/stats/overview'),
      axios.get('/api/stats/alarms/types'),
      axios.get('/api/stats/alarms/levels'),
      axios.get('/api/stats/alarms/trend'),
    ])
      .then(([o, t, l, tr]) => {
        setOverview(o.data || {})
        setTypes(t.data || [])
        setLevels(l.data || [])
        setTrend(tr.data || [])
      })
      .catch(() => { /* 失败保持空数据 */ })
      .finally(() => setLoading(false))
  }, [])

  // 告警趋势（折线）
  const trendOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, bottom: 40 },
    xAxis: {
      type: 'category',
      data: trend.map(x => String(x.key).substring(5, 16)),
      axisLabel: { rotate: 30, fontSize: 10 },
    },
    yAxis: { type: 'value' },
    series: [{
      type: 'line', smooth: true, data: trend.map(x => x.count),
      areaStyle: { opacity: 0.2 }, itemStyle: { color: '#91cc75' },
    }],
  }

  // 类型分布（饼图）
  const typeOption = {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, type: 'scroll' },
    series: [{
      type: 'pie', radius: ['40%', '65%'], center: ['50%', '45%'],
      data: types.map(x => ({ name: x.key, value: x.count })),
      label: { formatter: '{b}\n{c}' },
    }],
  }

  // 级别分布（柱状）
  const levelOption = {
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, bottom: 30 },
    xAxis: { type: 'category', data: levels.map(x => x.key) },
    yAxis: { type: 'value' },
    series: [{
      type: 'bar', barWidth: '40%',
      data: levels.map(x => x.count),
      itemStyle: { color: '#5470c6' },
      label: { show: true, position: 'top' },
    }],
  }

  return (
    <Spin spinning={loading}>
      <Row gutter={16}>
        <Col span={4}><Card><Statistic title="设备总数" value={overview.deviceTotal || 0} /></Card></Col>
        <Col span={4}>
          <Card><Statistic title="在线设备" value={overview.deviceOnline || 0}
                           valueStyle={{ color: '#3f8600' }} /></Card>
        </Col>
        <Col span={4}>
          <Card><Statistic title="离线设备" value={overview.deviceOffline || 0}
                           valueStyle={{ color: '#cf1322' }} /></Card>
        </Col>
        <Col span={4}><Card><Statistic title="告警总数" value={overview.alarmTotal || 0} /></Card></Col>
        <Col span={4}>
          <Card><Statistic title="待处理告警" value={overview.alarmPending || 0}
                           valueStyle={{ color: '#d48806' }} /></Card>
        </Col>
        <Col span={4}>
          <Card><Statistic title="影像已归档" value={overview.mediaUploaded || 0}
                           valueStyle={{ color: '#1677ff' }} /></Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={14}>
          <Card title="告警趋势（按小时 · Elasticsearch 聚合）">
            <Chart option={trendOption} />
          </Card>
        </Col>
        <Col span={10}>
          <Card title="告警类型分布（Elasticsearch 聚合）">
            <Chart option={typeOption} />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={12}>
          <Card title="告警级别分布（Elasticsearch 聚合）">
            <Chart option={levelOption} height={260} />
          </Card>
        </Col>
      </Row>
    </Spin>
  )
}
