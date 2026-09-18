import { useEffect, useState } from 'react'
import {
  Table, Tag, Card, Select, Space, Button, Modal, Input, Form,
  DatePicker, message, Drawer, Descriptions, Timeline, Progress, Switch,
} from 'antd'
import axios from 'axios'

const { RangePicker } = DatePicker

// ---------- 任务状态：六态 ----------
// 注意 DISPATCHED 与 RUNNING 内部是分开的，但对用户都体现为"执行中"这一组
const STATUS_LABEL = {
  CREATED: '草稿',
  DISPATCHED: '已下发',
  RUNNING: '执行中',
  FINISHED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const STATUS_COLOR = {
  CREATED: 'default',
  DISPATCHED: 'cyan',
  RUNNING: 'blue',
  FINISHED: 'green',
  FAILED: 'red',
  CANCELLED: 'default',
}

const TASK_TYPES = [
  { value: 'ROUTINE', label: '空中巡查' },
  { value: 'SPECIAL', label: '专项任务' },
  { value: 'REVIEW', label: '地面复核' },
]

const typeLabel = (t) => TASK_TYPES.find(x => x.value === t)?.label || t

// 回执阶段的中文名
const STAGE_LABEL = {
  STARTED: '任务开始执行',
  PROGRESS: '执行中',
  FINISHED: '任务执行完成',
  FAILED: '执行失败',
}

export default function TaskManage() {
  const [tasks, setTasks] = useState([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)

  const [filters, setFilters] = useState({})
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)

  // 新建弹窗
  const [createOpen, setCreateOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm()
  const [devices, setDevices] = useState([])

  // 详情抽屉
  const [detailOpen, setDetailOpen] = useState(false)
  const [detail, setDetail] = useState(null)

  const updateFilter = (key, value) => {
    setFilters(prev => ({ ...prev, [key]: value }))
    setPage(1)
  }

  const resetFilters = () => {
    setFilters({})
    setPage(1)
  }

  const load = () => {
    setLoading(true)
    const params = { page, size: pageSize }
    if (filters.status && filters.status !== 'ALL') params.status = filters.status
    if (filters.deviceNo) params.deviceNo = filters.deviceNo
    if (filters.taskType) params.taskType = filters.taskType
    if (filters.range?.[0]) params.startTime = filters.range[0].valueOf()
    if (filters.range?.[1]) params.endTime = filters.range[1].valueOf()

    axios.get('/api/tasks', { params })
      .then(res => {
        setTasks(res.data?.items || [])
        setTotal(res.data?.total || 0)
      })
      .catch(() => { setTasks([]); setTotal(0) })
      .finally(() => setLoading(false))
  }

  // 5 秒轮询：任务状态会随设备回执推进，不用手动刷新就能看到进度变化
  useEffect(() => {
    load()
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [filters, page, pageSize])

  // 设备下拉选项（只列在线且未停用的）
  const loadDevices = () => {
    axios.get('/api/tasks/available-devices')
      .then(res => setDevices(res.data || []))
      .catch(() => setDevices([]))
  }

  // 批次下拉选项（从当前任务列表里取不同的设备号，够用且无需额外接口）
  const deviceOptions = Array.from(new Set(tasks.map(t => t.deviceNo)))
    .map(v => ({ value: v, label: v }))

  const openCreate = () => {
    form.resetFields()
    form.setFieldsValue({ taskType: 'ROUTINE', autoDispatch: true })
    loadDevices()
    setCreateOpen(true)
  }

  const submitCreate = () => {
    form.validateFields().then(values => {
      setSubmitting(true)
      const { autoDispatch, ...body } = values
      axios.post('/api/tasks', body, { params: { autoDispatch } })
        .then(res => {
          message.success(autoDispatch
            ? `任务 ${res.data.taskId} 已创建并下发`
            : `任务 ${res.data.taskId} 已保存为草稿`)
          setCreateOpen(false)
          load()
        })
        .catch(e => message.error(e.response?.data?.message || '创建失败'))
        .finally(() => setSubmitting(false))
    })
  }

  const doDispatch = (taskId) => {
    axios.post(`/api/tasks/${taskId}/dispatch`)
      .then(() => { message.success('已下发'); load() })
      .catch(e => message.error(e.response?.data?.message || '下发失败'))
  }

  const doCancel = (taskId) => {
    Modal.confirm({
      title: '确认取消该任务？',
      onOk: () => axios.post(`/api/tasks/${taskId}/cancel`, { reason: '值班员取消' })
        .then(() => { message.success('已取消'); load() })
        .catch(e => message.error(e.response?.data?.message || '取消失败')),
    })
  }

  const openDetail = (taskId) => {
    axios.get(`/api/tasks/${taskId}`)
      .then(res => { setDetail(res.data); setDetailOpen(true) })
      .catch(() => message.error('加载详情失败'))
  }

  const columns = [
    { title: '任务编号', dataIndex: 'taskId', width: 190 },
    { title: '设备', dataIndex: 'deviceNo', width: 100 },
    { title: '类型', dataIndex: 'taskType', width: 100, render: typeLabel },
    {
      title: '进度', dataIndex: 'progress', width: 140,
      render: p => <Progress percent={p ?? 0} size="small" />,
    },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: s => <Tag color={STATUS_COLOR[s]}>{STATUS_LABEL[s] || s}</Tag>,
    },
    {
      title: '创建时间', dataIndex: 'createTime', width: 170,
      render: t => t ? new Date(t).toLocaleString() : '—',
    },
    {
      title: '操作', width: 160, fixed: 'right',
      render: (_, r) => (
        <Space size="small">
          <Button type="link" size="small" onClick={() => openDetail(r.taskId)}>详情</Button>
          {(r.status === 'CREATED' || r.status === 'FAILED') && (
            <Button type="link" size="small" onClick={() => doDispatch(r.taskId)}>
              {r.status === 'FAILED' ? '重发' : '下发'}
            </Button>
          )}
          {['CREATED', 'DISPATCHED', 'RUNNING'].includes(r.status) && (
            <Button type="link" size="small" danger onClick={() => doCancel(r.taskId)}>取消</Button>
          )}
        </Space>
      ),
    },
  ]

  return (
    <>
      <Card
        title={`巡检任务（共 ${total} 条）`}
        extra={<Button type="primary" onClick={openCreate}>新建任务</Button>}
      >
        {/* ---------- 多条件筛选 ---------- */}
        <Space wrap style={{ marginBottom: 16 }}>
          <span>状态：</span>
          <Select value={filters.status} style={{ width: 120 }} allowClear placeholder="全部"
                  onChange={v => updateFilter('status', v)}
                  options={Object.entries(STATUS_LABEL).map(([value, label]) => ({ value, label }))} />

          <span>设备：</span>
          <Select placeholder="全部设备" allowClear style={{ width: 130 }}
                  value={filters.deviceNo}
                  onChange={v => updateFilter('deviceNo', v)}
                  options={deviceOptions} />

          <span>类型：</span>
          <Select placeholder="全部类型" allowClear style={{ width: 130 }}
                  value={filters.taskType}
                  onChange={v => updateFilter('taskType', v)}
                  options={TASK_TYPES} />

          <span>创建时间：</span>
          <RangePicker showTime value={filters.range}
                       onChange={v => updateFilter('range', v)} />

          <Button onClick={resetFilters}>重置</Button>
        </Space>

        <Table
          rowKey="taskId"
          columns={columns}
          dataSource={tasks}
          loading={loading}
          scroll={{ x: 1100 }}
          size="small"
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: t => `共 ${t} 条`,
            onChange: (p, s) => { setPage(p); setPageSize(s) },
          }}
        />
      </Card>

      {/* ---------- 新建任务 ---------- */}
      <Modal
        title="新建巡检任务"
        open={createOpen}
        onOk={submitCreate}
        onCancel={() => setCreateOpen(false)}
        confirmLoading={submitting}
        okText="提交"
        cancelText="取消"
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="taskType" label="任务类型" rules={[{ required: true }]}>
            <Select options={TASK_TYPES} />
          </Form.Item>

          <Form.Item name="deviceNo" label="目标设备" rules={[{ required: true, message: '请选择目标设备' }]}
                     extra={devices.length === 0 ? '当前没有在线可用设备 —— 请先启动仿真端' : ''}>
            <Select
              placeholder="只列出在线且未停用的设备"
              options={devices.map(d => ({
                value: d.deviceNo,
                label: `${d.deviceNo}（${d.deviceType === 'DRONE' ? '无人机' : '机器狗'} · 电量 ${d.battery ?? '—'}%）`,
              }))}
            />
          </Form.Item>

          <Form.Item name="area" label="作业区域">
            <Input placeholder="如：园区北侧围墙一线" />
          </Form.Item>

          <Form.Item name="description" label="任务说明">
            <Input.TextArea rows={3} placeholder="选填" />
          </Form.Item>

          <Form.Item name="autoDispatch" label="立即下发" valuePropName="checked"
                     extra="关闭则只保存为草稿，稍后可手动下发">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      {/* ---------- 任务详情：台账 + 回执时间轴（UC-13）---------- */}
      <Drawer
        title={detail?.task?.taskId || '任务详情'}
        width={560}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
      >
        {detail?.task && (
          <>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[detail.task.status]}>
                  {STATUS_LABEL[detail.task.status] || detail.task.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="设备">
                {detail.task.deviceNo}（{detail.task.deviceType === 'DRONE' ? '无人机' : '机器狗'}）
              </Descriptions.Item>
              <Descriptions.Item label="类型">{typeLabel(detail.task.taskType)}</Descriptions.Item>
              <Descriptions.Item label="作业区域">{detail.task.area || '—'}</Descriptions.Item>
              <Descriptions.Item label="任务说明">{detail.task.description || '—'}</Descriptions.Item>
              <Descriptions.Item label="创建人">{detail.task.createBy || '—'}</Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {detail.task.createTime ? new Date(detail.task.createTime).toLocaleString() : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="下发时间">
                {detail.task.dispatchTime ? new Date(detail.task.dispatchTime).toLocaleString() : '未下发'}
              </Descriptions.Item>
              <Descriptions.Item label="下发次数">{detail.task.dispatchCount ?? 0}</Descriptions.Item>
              <Descriptions.Item label="关联告警">{detail.task.alarmId || '—'}</Descriptions.Item>
              <Descriptions.Item label="备注">{detail.task.remark || '—'}</Descriptions.Item>
            </Descriptions>

            <h4 style={{ marginTop: 24 }}>执行回执</h4>
            {detail.acks?.length > 0 ? (
              <Timeline
                style={{ marginTop: 16 }}
                items={detail.acks.map(a => ({
                  color: a.stage === 'FINISHED' ? 'green'
                    : a.stage === 'FAILED' ? 'red' : 'blue',
                  children: (
                    <div>
                      <b>{STAGE_LABEL[a.stage] || a.stage}</b>
                      {a.progress != null && <span>（{a.progress}%）</span>}
                      <div style={{ color: '#888', fontSize: 12 }}>
                        {new Date(a.eventTime).toLocaleString()}
                        {a.remark ? ` · ${a.remark}` : ''}
                      </div>
                    </div>
                  ),
                }))}
              />
            ) : (
              <p style={{ color: '#999' }}>暂无回执 —— 任务尚未下发，或设备未响应。</p>
            )}
          </>
        )}
      </Drawer>
    </>
  )
}
