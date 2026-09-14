import { Tabs } from 'antd'
  import DeviceList from './pages/DeviceList'
  import AlarmList from './pages/AlarmList'
  import DeviceMap from './pages/DeviceMap'
  import AlarmSearch from './pages/AlarmSearch'

  export default function App() {
    return (
      <div style={{ padding: 24, background: '#f5f5f5', minHeight: '100vh' }}>
        <h2>🛰️ 无人机-机器狗空地协同巡检平台</h2>
        <Tabs
          defaultActiveKey="devices"
          items={[
            { key: 'devices', label: '设备列表', children: <DeviceList /> },
            { key: 'alarms', label: '告警列表', children: <AlarmList /> },
            { key: 'map', label: '设备地图', children: <DeviceMap /> },
            { key: 'search', label: '告警检索', children: <AlarmSearch /> },
          ]}
        />
      </div>
    )
  }