import { useState } from 'react'
import { Card, Form, Input, Button, message } from 'antd'
import axios from 'axios'

export default function Login({ onLogin }) {
  const [loading, setLoading] = useState(false)

  const submit = (values) => {
    setLoading(true)
    axios.post('/api/auth/login', values)
      .then(res => {
        localStorage.setItem('token', res.data.token)
        localStorage.setItem('user', JSON.stringify(res.data))
        message.success(`欢迎，${res.data.realName || res.data.username}`)
        onLogin(res.data)
      })
      .catch(e => message.error(e.response?.data?.message || '登录失败，请检查用户名密码'))
      .finally(() => setLoading(false))
  }

  return (
    <div style={{
      display: 'flex', justifyContent: 'center', alignItems: 'center',
      minHeight: '100vh', background: '#f0f2f5',
    }}>
      <Card title="🛰️ 无人机-机器狗空地协同巡检平台" style={{ width: 390 }}>
        <Form onFinish={submit} layout="vertical">
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="用户名" size="large" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password placeholder="密码" size="large" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={loading}>
            登 录
          </Button>
        </Form>

        <div style={{ marginTop: 16, fontSize: 12, color: '#999', lineHeight: 1.9 }}>
          演示账号：<br />
          <b>admin</b> / admin123 —— 系统管理员<br />
          <b>operator</b> / 123456 —— 巡检值班员<br />
          <b>ops</b> / 123456 —— 系统运维人员
        </div>
      </Card>
    </div>
  )
}
