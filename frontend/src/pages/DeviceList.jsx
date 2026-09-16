import { useEffect, useState } from 'react'
import { Table, Tag, Card, Button, Space, Modal, Form, Input, Select, Popconfirm, message } from 'antd'
import axios from 'axios'

/** 毫秒 → 可读时长（如 "2 小时 13 分"） */
const formatDuration = (ms) => {
  if (!ms || ms < 0) return '—'
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h} 小时 ${m} 分`
  if (m > 0) return `${m} 分 ${s} 秒`
  return `${s} 秒`
}

export default function DeviceList({ role }) {
  const [devices, setDevices] = useState([])
  const [loading, setLoading] = useState(false)

  // 新增 / 编辑弹窗
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState(null)      // null = 新增；否则为被编辑的设备
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm()

  /** 只有系统管理员能执行设备的新增/编辑/停用/删除（后端拦截器同样会校验，前端只是隐藏入口） */
  const isAdmin = role === 'ADMIN'

  // showLoading：首次加载显示 loading；轮询时静默刷新（避免每 5 秒闪一下）
  const load = (showLoading = false) => {
    if (showLoading) setLoading(true)
    axios.get('/api/devices')
      .then(res => setDevices(res.data || []))
      .catch(() => { /* 轮询失败静默处理，下次自动重试 */ })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load(true)                                        // 首次加载
    const timer = setInterval(() => load(), 5000)     // 每 5 秒静默刷新（NFR-05 实时刷新）
    return () => clearInterval(timer)                 // 组件卸载时清理定时器
  }, [])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ deviceType: 'DRONE' })
    setFormOpen(true)
  }

  const openEdit = (record) => {
    setEditing(record)
    form.setFieldsValue(record)
    setFormOpen(true)
  }

  const submit = () => {
    form.validateFields().then(values => {
      setSubmitting(true)
      const req = editing
        ? axios.put(`/api/devices/${editing.deviceNo}`, values)
        : axios.post('/api/devices', values)

      req
        .then(res => {
          message.success(editing ? '设备已更新' : `设备 ${res.data?.deviceNo} 已新增`)
          setFormOpen(false)
          load()
        })
        .catch(e => message.error(e.response?.data?.message || '操作失败'))
        .finally(() => setSubmitting(false))
    })
  }

  const disable = (record) => {
    axios.put(`/api/devices/${record.deviceNo}/disable`)
      .then(() => {
        message.success(`设备 ${record.deviceNo} 已停用`)
        load()
      })
      .catch(() => message.error('停用失败'))
  }

  const remove = (record) => {
    axios.delete(`/api/devices/${record.deviceNo}`)
      .then(() => {
        message.success(`设备 ${record.deviceNo} 已删除`)
        load()
      })
      .catch(() => message.error('删除失败'))
  }

  const enable = (record) => {
    axios.put(`/api/devices/${record.deviceNo}/enable`)
      .then(() => {
        message.success(`设备 ${record.deviceNo} 已启用`)
        load()
      })
      .catch(() => message.error('启用失败'))
  }

  const columns = [
    { title: '设备编号', dataIndex: 'deviceNo', width: 110 },
    { title: '名称', dataIndex: 'deviceName', width: 150, render: v => v || '—' },
    {
      title: '类型', dataIndex: 'deviceType', width: 90,
      render: t => (t === 'DRONE' ? '无人机' : '机器狗'),
    },
    { title: '区域', dataIndex: 'area', width: 120, render: v => v || '—' },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (s, r) => r.enabled === false
        ? <Tag color="default">已停用</Tag>
        : <Tag color={s === 'ONLINE' ? 'green' : 'red'}>{s}</Tag>,
    },
    { title: '电量', dataIndex: 'battery', width: 80, render: b => `${b}%` },
    {
      title: '在线时长', dataIndex: 'onlineSinceTime', width: 130,
      render: (v, r) => (r.enabled !== false && r.status === 'ONLINE' && v
        ? formatDuration(Date.now() - v) : '—'),
    },
    {
      title: '操作', width: 190, fixed: 'right',
      render: (_, record) => (
        !isAdmin
          ? <span style={{ color: '#bbb' }}>—</span>
          : (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => openEdit(record)}>编辑</Button>
          {record.enabled === false ? (
            <Button type="link" size="small" onClick={() => enable(record)}>启用</Button>
          ) : (
            <Popconfirm
              title={`确认停用 ${record.deviceNo}？`}
              description="停用后该设备不再参与任务派发（台账保留，可重新启用）"
              onConfirm={() => disable(record)}
              okText="停用" cancelText="取消"
            >
              <Button type="link" size="small" danger>停用</Button>
            </Popconfirm>
          )}
          <Popconfirm
            title={`确认删除 ${record.deviceNo}？`}
            description="台账将被移除（历史轨迹/告警数据保留）。若设备仍在线，删除后会因心跳重新注册。"
            onConfirm={() => remove(record)}
            okText="删除" okButtonProps={{ danger: true }} cancelText="取消"
          >
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
          )
      ),
    },
  ]

  return (
    <>
      <Card
        title="设备列表（每 5 秒自动刷新）"
        extra={isAdmin && <Button type="primary" onClick={openCreate}>新增设备</Button>}
      >
        <Table
          rowKey="deviceNo"
          columns={columns}
          dataSource={devices}
          loading={loading}
          scroll={{ x: 1120 }}
          pagination={{ pageSize: 10 }}
        />
      </Card>

      <Modal
        title={editing ? `编辑设备：${editing.deviceNo}` : '新增设备'}
        open={formOpen}
        onOk={submit}
        onCancel={() => setFormOpen(false)}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
      >
        <Form form={form} layout="vertical">
          <Form.Item label="设备编号">
            <Input
              value={editing ? editing.deviceNo : '保存后由系统自动生成'}
              disabled
            />
          </Form.Item>

          <Form.Item name="deviceName" label="设备名称">
            <Input placeholder="如 1号巡检无人机" />
          </Form.Item>

          <Form.Item
            name="deviceType" label="设备类型"
            rules={[{ required: true, message: '请选择设备类型' }]}
          >
            <Select
              disabled={!!editing}
              options={[
                { value: 'DRONE', label: '无人机' },
                { value: 'ROBOT_DOG', label: '机器狗' },
              ]}
            />
          </Form.Item>

          <Form.Item name="model" label="型号">
            <Input placeholder="如 M30T" />
          </Form.Item>

          <Form.Item name="area" label="所属区域">
            <Input placeholder="如 A区围墙" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
