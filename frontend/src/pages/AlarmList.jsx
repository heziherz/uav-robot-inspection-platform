import { useEffect, useState } from 'react'
import { Table, Tag, Card, Select, Space, Button, Modal, Input, Radio, DatePicker, message } from 'antd'
import axios from 'axios'

const { RangePicker } = DatePicker

// 告警级别配色
const levelColor = (level) => {
  if (level === 'CRITICAL') return 'red'
  if (level === 'WARN') return 'orange'
  return 'blue'
}

// 处置状态配色
const statusColor = (s) => {
  if (s === 'PENDING') return 'gold'
  if (s === 'REVIEWING') return 'blue'
  return 'green'
}

const ALARM_TYPES = ['INTRUSION', 'SUSPICIOUS', 'ENV', 'OVERHEAT', 'FAULT', 'FENCE']
const LEVELS = ['INFO', 'WARN', 'CRITICAL']

export default function AlarmList() {
  const [alarms, setAlarms] = useState([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)

  // 筛选条件（可任意组合）
  const [filters, setFilters] = useState({ status: 'PENDING' })
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [selectedKeys, setSelectedKeys] = useState([])

  const [devices, setDevices] = useState([])          // 设备下拉选项

  // 处置弹窗（batchMode=false 单条；true 批量）
  const [modalOpen, setModalOpen] = useState(false)
  const [batchMode, setBatchMode] = useState(false)
  const [handling, setHandling] = useState(null)
  const [action, setAction] = useState('CLOSE')
  const [remark, setRemark] = useState('')
  const [submitting, setSubmitting] = useState(false)

  /** 改任意筛选条件 → 回到第 1 页并清空选择 */
  const updateFilter = (key, value) => {
    setFilters(prev => ({ ...prev, [key]: value }))
    setPage(1)
    setSelectedKeys([])
  }

  const resetFilters = () => {
    setFilters({ status: 'PENDING' })
    setPage(1)
    setSelectedKeys([])
  }

  const load = () => {
    setLoading(true)
    const params = { page, size: pageSize }
    if (filters.status && filters.status !== 'ALL') params.status = filters.status
    if (filters.deviceNo) params.deviceNo = filters.deviceNo
    if (filters.alarmType) params.alarmType = filters.alarmType
    if (filters.level) params.level = filters.level
    if (filters.range?.[0]) params.startTime = filters.range[0].valueOf()
    if (filters.range?.[1]) params.endTime = filters.range[1].valueOf()

    axios.get('/api/alarms', { params })
      .then(res => {
        setAlarms(res.data?.items || [])
        setTotal(res.data?.total || 0)
      })
      .catch(() => { setAlarms([]); setTotal(0) })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [filters, page, pageSize])

  // 设备下拉选项
  useEffect(() => {
    axios.get('/api/devices')
      .then(res => setDevices((res.data || []).map(d => ({ value: d.deviceNo, label: d.deviceNo }))))
      .catch(() => { /* 静默 */ })
  }, [])

  const openHandle = (record) => {
    setHandling(record)
    setBatchMode(false)
    setAction('CLOSE')
    setRemark('')
    setModalOpen(true)
  }

  const openBatch = () => {
    if (selectedKeys.length === 0) {
      message.warning('请先勾选要处置的告警')
      return
    }
    setBatchMode(true)
    setAction('CLOSE')
    setRemark('')
    setModalOpen(true)
  }

  const submit = () => {
    setSubmitting(true)
    const payload = { action, handleBy: '值班员', remark }

    const req = batchMode
      ? axios.put('/api/alarms/handle-batch', { ...payload, alarmIds: selectedKeys })
      : axios.put(`/api/alarms/${handling.alarmId}/handle`, payload)

    req
      .then(res => {
        if (batchMode) {
          message.success(`已批量处置 ${res.data?.handled ?? selectedKeys.length} 条告警`)
          setSelectedKeys([])
        } else {
          message.success(action === 'REVIEW' ? '已指派机器狗抵近复核' : '告警已确认关闭')
        }
        setModalOpen(false)
        load()
      })
      .catch(() => message.error('处置失败'))
      .finally(() => setSubmitting(false))
  }

  const columns = [
    { title: '告警编号', dataIndex: 'alarmId', ellipsis: true, width: 220 },
    { title: '设备', dataIndex: 'deviceNo', width: 100 },
    { title: '类型', dataIndex: 'alarmType', width: 110 },
    {
      title: '级别', dataIndex: 'level', width: 100,
      render: l => <Tag color={levelColor(l)}>{l}</Tag>,
    },
    { title: '描述', dataIndex: 'description', width: 240 },
    {
      title: '发生时间', dataIndex: 'eventTime', width: 180,
      render: t => new Date(t).toLocaleString(),
    },
    {
      title: '状态', dataIndex: 'handleStatus', width: 110,
      render: s => <Tag color={statusColor(s)}>{s}</Tag>,
    },
    {
      title: '操作', width: 90, fixed: 'right',
      render: (_, record) => (
        record.handleStatus === 'PENDING'
          ? <Button type="link" size="small" onClick={() => openHandle(record)}>处置</Button>
          : <span style={{ color: '#bbb' }}>—</span>
      ),
    },
  ]

  return (
    <>
      <Card
        title={`告警列表（共 ${total} 条）`}
        extra={
          <Button type="primary" disabled={selectedKeys.length === 0} onClick={openBatch}>
            批量处置{selectedKeys.length > 0 ? `（${selectedKeys.length}）` : ''}
          </Button>
        }
      >
        {/* ---------- 多条件筛选 ---------- */}
        <Space wrap style={{ marginBottom: 16 }}>
          <span>处置状态：</span>
          <Select value={filters.status} style={{ width: 120 }}
                  onChange={v => updateFilter('status', v)}
                  options={[
                    { value: 'PENDING', label: '待处理' },
                    { value: 'REVIEWING', label: '复核中' },
                    { value: 'HANDLED', label: '已处置' },
                    { value: 'ALL', label: '全部' },
                  ]} />

          <span>设备：</span>
          <Select placeholder="全部设备" allowClear style={{ width: 130 }}
                  value={filters.deviceNo}
                  onChange={v => updateFilter('deviceNo', v)}
                  options={devices} />

          <span>类型：</span>
          <Select placeholder="全部类型" allowClear style={{ width: 140 }}
                  value={filters.alarmType}
                  onChange={v => updateFilter('alarmType', v)}
                  options={ALARM_TYPES.map(v => ({ value: v, label: v }))} />

          <span>级别：</span>
          <Select placeholder="全部级别" allowClear style={{ width: 120 }}
                  value={filters.level}
                  onChange={v => updateFilter('level', v)}
                  options={LEVELS.map(v => ({ value: v, label: v }))} />

          <span>时间：</span>
          <RangePicker showTime
                       value={filters.range}
                       onChange={v => updateFilter('range', v)} />

          <Button onClick={resetFilters}>重置</Button>
        </Space>

        <Table
          rowKey="alarmId"
          columns={columns}
          dataSource={alarms}
          loading={loading}
          rowSelection={{
            selectedRowKeys: selectedKeys,
            onChange: setSelectedKeys,
            preserveSelectedRowKeys: true,
          }}
          scroll={{ x: 1250 }}
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

      <Modal
        title={batchMode ? `批量处置（已选 ${selectedKeys.length} 条）` : '处置告警'}
        open={modalOpen}
        onOk={submit}
        onCancel={() => setModalOpen(false)}
        confirmLoading={submitting}
        okText="提交"
        cancelText="取消"
      >
        {!batchMode && handling && (
          <p style={{ color: '#666', marginBottom: 16 }}>
            {handling.deviceNo} · {handling.alarmType} · {handling.description}
          </p>
        )}
        <Radio.Group value={action} onChange={e => setAction(e.target.value)}
                     style={{ marginBottom: 16 }}>
          <Radio value="CLOSE">确认并关闭</Radio>
          <Radio value="REVIEW">指派机器狗抵近复核</Radio>
        </Radio.Group>
        <Input.TextArea
          rows={3}
          placeholder="处置备注（可选）"
          value={remark}
          onChange={e => setRemark(e.target.value)}
        />
      </Modal>
    </>
  )
}
