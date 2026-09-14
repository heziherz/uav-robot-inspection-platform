import { useEffect, useState } from 'react'
  import { Table, Tag, Card, Select, Space } from 'antd'
  import axios from 'axios'

  // 告警级别配色
  const levelColor = (level) => {
    if (level === 'CRITICAL') return 'red'
    if (level === 'WARN') return 'orange'
    return 'blue'
  }

  export default function AlarmList() {
    const [alarms, setAlarms] = useState([])
    const [loading, setLoading] = useState(false)
    const [status, setStatus] = useState('PENDING')   // 默认只看待处理

    useEffect(() => {
      setLoading(true)
      const params = status === 'ALL' ? {} : { status }
      axios.get('/api/alarms', { params })
        .then(res => setAlarms(res.data))
        .catch(() => setAlarms([]))
        .finally(() => setLoading(false))
    }, [status])

    const columns = [
      { title: '告警编号', dataIndex: 'alarmId', ellipsis: true, width: 220 },
      { title: '设备', dataIndex: 'deviceNo', width: 100 },
      { title: '类型', dataIndex: 'alarmType', width: 110 },
      {
        title: '级别', dataIndex: 'level', width: 100,
        render: l => <Tag color={levelColor(l)}>{l}</Tag>,
      },
      { title: '描述', dataIndex: 'description',width:260 },
      {
        title: '发生时间', dataIndex: 'eventTime', width: 180,
        render: t => new Date(t).toLocaleString(),
      },
      {
        title: '状态', dataIndex: 'handleStatus', width: 100,
        render: s => <Tag color={s === 'PENDING' ? 'gold' : 'green'}>{s}</Tag>,
      },
    ]

    return (
      <Card
        title="告警列表"
        extra={
          <Space>
            <span>处置状态：</span>
            <Select
              value={status}
              style={{ width: 140 }}
              onChange={setStatus}
              options={[
                { value: 'PENDING', label: '待处理' },
                { value: 'HANDLED', label: '已处置' },
                { value: 'ALL', label: '全部' },
              ]}
            />
          </Space>
        }
      >
        <Table
          rowKey="alarmId"
          columns={columns}
          dataSource={alarms}
          loading={loading}
          scroll={{ x: 1100 }}
          size="small"
          pagination={{ pageSize: 10 }}
        />
      </Card>
    )
  }