import { useEffect, useRef } from 'react'
  import { Card, Tag } from 'antd'
  import axios from 'axios'
  import L from 'leaflet'
  import 'leaflet/dist/leaflet.css'

  // —— 修复 Leaflet 默认图标在打包后丢失的问题（经典坑）——
  import iconUrl from 'leaflet/dist/images/marker-icon.png'
  import iconRetinaUrl from 'leaflet/dist/images/marker-icon-2x.png'
  import shadowUrl from 'leaflet/dist/images/marker-shadow.png'

  delete L.Icon.Default.prototype._getIconUrl
  L.Icon.Default.mergeOptions({ iconUrl, iconRetinaUrl, shadowUrl })

  // 园区中心坐标（与仿真端的路线区域一致）
  const PARK_CENTER = [29.5630, 106.5500]

  export default function DeviceMap() {
    const mapRef = useRef(null)        // 地图容器 DOM
    const mapObj = useRef(null)        // Leaflet 地图实例
    const markers = useRef([])         // 已画的标记点

    useEffect(() => {
      // ① 初始化地图（只做一次）
      if (!mapObj.current) {
        mapObj.current = L.map(mapRef.current).setView(PARK_CENTER, 16)
        L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',
  {
          subdomains: ['1', '2', '3', '4'],
          attribution: '&copy; 高德地图',
        }).addTo(mapObj.current)
      }

      const refresh = () => {
        axios.get('/api/telemetry/latest')
          .then(res => {
            const map = mapObj.current
            // ② 清掉旧标记
            markers.current.forEach(m => map.removeLayer(m))
            markers.current = []

            // ③ 每台设备打一个点
            res.data.forEach(pos => {
              const isDrone = String(pos.deviceNo).startsWith('UAV')
              const marker = L.circleMarker([pos.lat, pos.lng], {
                radius: 8,
                color: isDrone ? '#1677ff' : '#52c41a',
                fillColor: isDrone ? '#1677ff' : '#52c41a',
                fillOpacity: 0.8,
              }).addTo(map)
              marker.bindPopup(
                `<b>${pos.deviceNo}</b><br/>` +
                `类型：${isDrone ? '无人机' : '机器狗'}<br/>` +
                `坐标：${pos.lat}, ${pos.lng}<br/>` +
                `高度：${pos.altitude ?? 0}m<br/>` +
                `更新时间：${new Date(pos.eventTime).toLocaleTimeString()}`
              )
              markers.current.push(marker)
            })
          })
          .catch(() => { /* 静默失败，下次轮询再试 */ })
      }

      refresh()
      const timer = setInterval(refresh, 5000)      // 每 5 秒刷新

      return () => clearInterval(timer)             // 组件卸载时清理定时器
    }, [])

    return (
      <Card
        title="设备实时分布（每 5 秒自动刷新）"
        extra={<span>
          <Tag color="#1677ff">无人机</Tag>
          <Tag color="#52c41a">机器狗</Tag>
        </span>}
      >
        {/* 地图容器必须有明确高度，否则不显示 */}
        <div ref={mapRef} style={{ height: 520, width: '100%' }} />
      </Card>
    )
  }