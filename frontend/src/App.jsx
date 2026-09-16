import { useState } from 'react'
import { Tabs, Button, Space, Tag } from 'antd'
import Login from './pages/Login'
import DeviceList from './pages/DeviceList'
import AlarmList from './pages/AlarmList'
import DeviceMap from './pages/DeviceMap'
import AlarmSearch from './pages/AlarmSearch'
import UserManage from './pages/UserManage'
import StatsDashboard from './pages/StatsDashboard'
import MediaManage from './pages/MediaManage'

/** 角色 → 中文名 */
const roleName = (r) =>
  ({ ADMIN: '系统管理员', OPERATOR: '巡检值班员', OPS: '系统运维人员' }[r] || r)

export default function App() {
  // 从 localStorage 恢复登录态（刷新页面不掉线）
  const [user, setUser] = useState(() => {
    const saved = localStorage.getItem('user')
    return saved ? JSON.parse(saved) : null
  })

  const logout = () => {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    setUser(null)
  }

  // 未登录 → 显示登录页
  if (!user) {
    return <Login onLogin={setUser} />
  }

  return (
    <div style={{ padding: 24, background: '#f5f5f5', minHeight: '100vh' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>🛰️ 无人机-机器狗空地协同巡检平台</h2>
        <Space>
          <Tag color="blue">{user.realName || user.username}</Tag>
          <Tag color="green">{roleName(user.role)}</Tag>
          <Button size="small" onClick={logout}>退出登录</Button>
        </Space>
      </div>

      <Tabs
        defaultActiveKey="stats"
        items={[
          { key: 'stats', label: '统计分析', children: <StatsDashboard /> },
          {
            key: 'devices', label: '设备列表',
            children: <DeviceList role={user.role} />,     // 传入角色：管理员才显示管理按钮
          },
          { key: 'alarms', label: '告警列表', children: <AlarmList /> },
          { key: 'map', label: '设备地图', children: <DeviceMap /> },
          { key: 'search', label: '告警检索', children: <AlarmSearch /> },
          { key: 'media', label: '巡检影像', children: <MediaManage /> },
          // 用户管理仅"系统管理员"可见（后端拦截器同样限制）
          ...(user.role === 'ADMIN'
            ? [{ key: 'users', label: '用户管理', children: <UserManage /> }]
            : []),
        ]}
      />
    </div>
  )
}
