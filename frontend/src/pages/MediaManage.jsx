import { useEffect, useState } from 'react'
import { Table, Tag, Card, Button, Space, Modal, Select, message } from 'antd'
import axios from 'axios'

/**
 * 巡检影像管理（UC-03 / 文件存储管理）。
 *
 * 【关键点】文件接口需要携带 JWT，而 <img src="/api/..."> 无法自定义请求头，
 *   所以这里统一用 axios 以 blob 方式取文件，再转成浏览器可显示的 objectURL。
 */
export default function MediaManage() {
  const [items, setItems] = useState([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(12)
  const [deviceNo, setDeviceNo] = useState()
  const [devices, setDevices] = useState([])

  // 预览弹窗
  const [previewUrl, setPreviewUrl] = useState(null)
  const [previewOpen, setPreviewOpen] = useState(false)

  const load = () => {
    setLoading(true)
    const params = { page, size: pageSize }
    if (deviceNo) params.deviceNo = deviceNo

    axios.get('/api/media', { params })
      .then(res => {
        setItems(res.data?.items || [])
        setTotal(res.data?.total || 0)
      })
      .catch(() => { /* 静默 */ })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [page, pageSize, deviceNo])

  // 设备下拉选项
  useEffect(() => {
    axios.get('/api/devices')
      .then(res => setDevices((res.data || []).map(d => ({ value: d.deviceNo, label: d.deviceNo }))))
      .catch(() => { /* 静默 */ })
  }, [])

  /** 取文件二进制（带 token） */
  const fetchBlob = (fileId) =>
    axios.get(`/api/media/${fileId}/download`, { responseType: 'blob' })

  const preview = (record) => {
    fetchBlob(record.fileId)
      .then(res => {
        setPreviewUrl(URL.createObjectURL(res.data))
        setPreviewOpen(true)
      })
      .catch(() => message.error('预览失败（文件可能尚未归档到 HDFS）'))
  }

  const download = (record) => {
    fetchBlob(record.fileId)
      .then(res => {
        const url = URL.createObjectURL(res.data)
        const a = document.createElement('a')
        a.href = url
        a.download = `${record.fileId}.jpg`
        a.click()
        URL.revokeObjectURL(url)
      })
      .catch(() => message.error('下载失败'))
  }

  const closePreview = () => {
    setPreviewOpen(false)
    if (previewUrl) URL.revokeObjectURL(previewUrl)
    setPreviewUrl(null)
  }

  const columns = [
    { title: '文件 ID', dataIndex: 'fileId', ellipsis: true, width: 260 },
    { title: '设备', dataIndex: 'deviceNo', width: 110 },
    {
      title: '类型', dataIndex: 'fileType', width: 110,
      render: t => <Tag color={t === 'INFRARED' ? 'purple' : 'blue'}>{t}</Tag>,
    },
    {
      title: '大小', dataIndex: 'size', width: 100,
      render: s => (s ? `${(s / 1024).toFixed(0)} KB` : '—'),
    },
    {
      title: '归档状态', dataIndex: 'uploaded', width: 120,
      render: u => (u
        ? <Tag color="green">已归档 HDFS</Tag>
        : <Tag color="orange">待重传</Tag>),
    },
    {
      title: '拍摄时间', dataIndex: 'eventTime', width: 180,
      render: t => new Date(t).toLocaleString(),
    },
    {
      title: '操作', width: 150, fixed: 'right',
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" disabled={!record.uploaded}
                  onClick={() => preview(record)}>预览</Button>
          <Button type="link" size="small" disabled={!record.uploaded}
                  onClick={() => download(record)}>下载</Button>
        </Space>
      ),
    },
  ]

  return (
    <>
      <Card
        title={`巡检影像（共 ${total} 条 · 文件存于 HDFS，元数据存 MongoDB）`}
        extra={
          <Space>
            <span>设备：</span>
            <Select
              placeholder="全部设备" allowClear style={{ width: 150 }}
              value={deviceNo}
              onChange={v => { setDeviceNo(v); setPage(1) }}
              options={devices}
            />
          </Space>
        }
      >
        <Table
          rowKey="fileId"
          columns={columns}
          dataSource={items}
          loading={loading}
          scroll={{ x: 1100 }}
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
        title="影像预览"
        open={previewOpen}
        onCancel={closePreview}
        footer={null}
        width={600}
      >
        {previewUrl && <img src={previewUrl} alt="预览" style={{ width: '100%' }} />}
      </Modal>
    </>
  )
}
