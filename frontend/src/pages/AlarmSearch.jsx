import { useState } from 'react'
  import { Card, Table, Select, Button, Space, Tag, message } from 'antd'
  import axios from 'axios'

  const levelColor = (level) => {
    if (level === 'CRITICAL') return 'red'
    if (level === 'WARN') return 'orange'
    return 'blue'
  }

  export default function AlarmSearch() {
    const [alarms, setAlarms] = useState([])
    const [loading, setLoading] = useState(false)
    const [type, setType] = useState()
    const [level, setLevel] = useState()

    const doSearch = () => {
      setLoading(true)
      const params = {}
      if (type) params.type = type
      if (level) params.level = level

      axios.get('/api/alarms/search', { params })
        .then(res => {
          setAlarms(res.data)
          message.success(`检索到 ${res.data.length} 条告警`)
        })
        .catch(() => message.error('检索失败'))
        .finally(() => setLoading(false))
    }

    const columns = [
      { title: '告警编号', dataIndex: 'alarmId', ellipsis: true, width: 220 },
      { title: '设备', dataIndex: 'deviceNo', width: 100 },
      { title: '类型', dataIndex: 'alarmType', width: 120 },
      { title: '级别', dataIndex: 'level', width: 100,
        render: l => <Tag color={levelColor(l)}>{l}</Tag> },
      { title: '描述', dataIndex: 'description', width: 260 },
      { title: '发生时间', dataIndex: 'eventTime', width: 200,
        render: t => new Date(t).toLocaleString() },
    ]

    return (
      <Card title="告警检索（Elasticsearch）">
        <Space style={{ marginBottom: 16 }} wrap>
          <span>告警类型：</span>
          <Select placeholder="全部类型" allowClear style={{ width: 150 }}
            value={type} onChange={setType}
            options={['INTRUSION', 'SUSPICIOUS', 'ENV', 'OVERHEAT', 'FAULT', 'FENCE']
              .map(v => ({ value: v, label: v }))} />
          <span>级别：</span>
          <Select placeholder="全部级别" allowClear style={{ width: 130 }}
            value={level} onChange={setLevel}
            options={['INFO', 'WARN', 'CRITICAL'].map(v => ({ value: v, label: v }))} />
          <Button type="primary" onClick={doSearch} loading={loading}>检索</Button>
        </Space>

        <Table rowKey="alarmId" columns={columns} dataSource={alarms}
          loading={loading} size="small" scroll={{ x: 1000 }}
          pagination={{ pageSize: 10 }} />
      </Card>
    )
  }