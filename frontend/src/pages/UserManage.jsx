import { useEffect, useState } from 'react'
import { Table, Tag, Card, Button, Space, Modal, Form, Input, Select, Popconfirm, message } from 'antd'
import axios from 'axios'

const roleName = (r) =>
  ({ ADMIN: '系统管理员', OPERATOR: '巡检值班员', OPS: '系统运维人员' }[r] || r)

export default function UserManage() {
  const [users, setUsers] = useState([])
  const [loading, setLoading] = useState(false)

  // 新增 / 编辑弹窗
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm()

  // 重置密码弹窗
  const [pwdOpen, setPwdOpen] = useState(false)
  const [pwdTarget, setPwdTarget] = useState(null)
  const [pwdForm] = Form.useForm()

  const load = () => {
    setLoading(true)
    axios.get('/api/users')
      .then(res => setUsers(res.data || []))
      .catch(() => message.error('加载用户列表失败'))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ role: 'OPERATOR' })
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
        ? axios.put(`/api/users/${editing.username}`, values)
        : axios.post('/api/users', values)

      req
        .then(() => {
          message.success(editing ? '用户已更新' : '用户已新增')
          setFormOpen(false)
          load()
        })
        .catch(e => message.error(e.response?.data?.message || '操作失败'))
        .finally(() => setSubmitting(false))
    })
  }

  const resetPwd = () => {
    pwdForm.validateFields().then(values => {
      axios.put(`/api/users/${pwdTarget.username}/password`, values)
        .then(() => {
          message.success(`已重置 ${pwdTarget.username} 的密码`)
          setPwdOpen(false)
        })
        .catch(e => message.error(e.response?.data?.message || '重置失败'))
    })
  }

  const toggleEnabled = (record) => {
    axios.put(`/api/users/${record.username}`, { enabled: !record.enabled })
      .then(() => {
        message.success(record.enabled ? '已停用' : '已启用')
        load()
      })
      .catch(() => message.error('操作失败'))
  }

  const remove = (record) => {
    axios.delete(`/api/users/${record.username}`)
      .then(() => {
        message.success('用户已删除')
        load()
      })
      .catch(e => message.error(e.response?.data?.message || '删除失败'))
  }

  const columns = [
    { title: '用户名', dataIndex: 'username', width: 130 },
    { title: '姓名', dataIndex: 'realName', width: 130, render: v => v || '—' },
    {
      title: '角色', dataIndex: 'role', width: 130,
      render: r => <Tag color="blue">{roleName(r)}</Tag>,
    },
    {
      title: '状态', dataIndex: 'enabled', width: 90,
      render: v => (v === false ? <Tag color="default">已停用</Tag> : <Tag color="green">启用</Tag>),
    },
    {
      title: '创建时间', dataIndex: 'createTime', width: 180,
      render: t => (t ? new Date(t).toLocaleString() : '—'),
    },
    {
      title: '最后登录', dataIndex: 'lastLoginTime', width: 180,
      render: t => (t ? new Date(t).toLocaleString() : '—'),
    },
    {
      title: '操作', width: 250, fixed: 'right',
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => openEdit(record)}>编辑</Button>
          <Button type="link" size="small" onClick={() => {
            setPwdTarget(record); pwdForm.resetFields(); setPwdOpen(true)
          }}>重置密码</Button>
          <Button type="link" size="small" onClick={() => toggleEnabled(record)}>
            {record.enabled === false ? '启用' : '停用'}
          </Button>
          <Popconfirm
            title={`确认删除用户 ${record.username}？`}
            onConfirm={() => remove(record)}
            okText="删除" okButtonProps={{ danger: true }} cancelText="取消"
          >
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <>
      <Card
        title="用户管理"
        extra={<Button type="primary" onClick={openCreate}>新增用户</Button>}
      >
        <Table
          rowKey="username"
          columns={columns}
          dataSource={users}
          loading={loading}
          scroll={{ x: 1150 }}
          pagination={{ pageSize: 10 }}
        />
      </Card>

      <Modal
        title={editing ? `编辑用户：${editing.username}` : '新增用户'}
        open={formOpen}
        onOk={submit}
        onCancel={() => setFormOpen(false)}
        confirmLoading={submitting}
        okText="保存" cancelText="取消"
      >
        <Form form={form} layout="vertical">
          {!editing && (
            <Form.Item name="username" label="用户名"
                       rules={[{ required: true, message: '请输入用户名' }]}>
              <Input placeholder="登录账号（不可修改）" />
            </Form.Item>
          )}
          {!editing && (
            <Form.Item name="password" label="密码"
                       rules={[{ required: true, min: 6, message: '密码至少 6 位' }]}>
              <Input.Password placeholder="至少 6 位" />
            </Form.Item>
          )}
          <Form.Item name="realName" label="姓名">
            <Input placeholder="真实姓名" />
          </Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true, message: '请选择角色' }]}>
            <Select
              options={[
                { value: 'OPERATOR', label: '巡检值班员' },
                { value: 'ADMIN', label: '系统管理员' },
                { value: 'OPS', label: '系统运维人员' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`重置密码：${pwdTarget?.username || ''}`}
        open={pwdOpen}
        onOk={resetPwd}
        onCancel={() => setPwdOpen(false)}
        okText="重置" cancelText="取消"
      >
        <Form form={pwdForm} layout="vertical">
          <Form.Item name="password" label="新密码"
                     rules={[{ required: true, min: 6, message: '密码至少 6 位' }]}>
            <Input.Password placeholder="至少 6 位" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
