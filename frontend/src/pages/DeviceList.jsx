import { useEffect, useState } from 'react'
  import { Table, Tag, Card, message } from 'antd'
  import axios from 'axios'

  export default function DeviceList() {
    const [devices, setDevices] = useState([])
    const [loading, setLoading] = useState(false)

    useEffect(() => {
      setLoading(true)
      axios.get('/api/devices')
        .then(res => setDevices(res.data))
        .catch(() => message.error('加载设备列表失败'))
        .finally(() => setLoading(false))
    }, [])

    const columns = [
      { title: '设备编号', dataIndex: 'deviceNo' },
      { title: '类型', dataIndex: 'deviceType',
        render: t => (t === 'DRONE' ? '无人机' : '机器狗') },
      { title: '状态', dataIndex: 'status',
        render: s => <Tag color={s === 'ONLINE' ? 'green' : 'red'}>{s}</Tag> },
      { title: '电量', dataIndex: 'battery', render: b => `${b}%` },
      { title: '心跳数', dataIndex: 'heartbeatCount' },
    ]

    return (
      <Card title="设备列表">
        <Table rowKey="deviceNo" columns={columns} dataSource={devices}
               loading={loading} pagination={false} />
      </Card>
    )
  }