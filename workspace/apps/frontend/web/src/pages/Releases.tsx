import { useMemo, useState, type HTMLAttributes } from 'react';
import { App, Button, Card, Progress, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  HolderOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  SaveOutlined,
} from '@ant-design/icons';
import {
  ENVS,
  NEXT_STATUS,
  PLATFORMS,
  SORTS,
  STATUSES,
  STATUS_ACTION,
  envLabel,
  filterReleases,
  fmtFull,
  platformLabel,
  relTime,
  sortReleases,
  statusColor,
  statusLabel,
  type Release,
} from '@acme/core';
import { useStore } from '../store/useStore';
import { EnvTag, PlatformTag, StatusTag } from '../components/atoms';
import { PageHead } from '../components/PageHead';
import { useDeleteReleases } from '../hooks/useReleaseActions';

const { Text } = Typography;

function DraggableRow(props: HTMLAttributes<HTMLTableRowElement>) {
  return <tr {...props} />;
}

export function Releases() {
  const { message } = App.useApp();
  const state = useStore((s) => s.state);
  const query = useStore((s) => s.query);
  const selection = useStore((s) => s.selection);
  const setQuery = useStore((s) => s.setQuery);
  const setSelection = useStore((s) => s.setSelection);
  const clearSelection = useStore((s) => s.clearSelection);
  const openDrawer = useStore((s) => s.openDrawer);
  const openModal = useStore((s) => s.openModal);
  const applyView = useStore((s) => s.applyView);
  const dispatch = useStore((s) => s.dispatch);
  const advance = useStore((s) => s.advance);
  const bulkAdvance = useStore((s) => s.bulkAdvance);
  const remove = useDeleteReleases();

  const [dragId, setDragId] = useState<string | null>(null);
  const [overId, setOverId] = useState<string | null>(null);

  const data = useMemo(
    () => sortReleases(filterReleases(state, query), query.sort, state.order),
    [state, query],
  );

  const onBulkAdvance = () => {
    const count = bulkAdvance();
    message[count ? 'success' : 'warning'](count ? `已推进 ${count} 个发布单` : '所选发布单没有可推进的状态');
  };

  const columns: ColumnsType<Release> = [
    {
      title: '',
      key: 'drag',
      width: 34,
      render: () => (
        <span className="drag-handle" title="拖拽调整优先级">
          <HolderOutlined />
        </span>
      ),
    },
    {
      title: '发布单',
      key: 'title',
      render: (_, r) => (
        <div>
          <Text strong>{r.title}</Text>
          <div className="cell-sub">{r.id} · P{r.priority}</div>
        </div>
      ),
    },
    {
      title: '应用 / 平台',
      key: 'app',
      render: (_, r) => {
        const app = state.apps.find((a) => a.id === r.appId);
        return (
          <div>
            <PlatformTag platform={r.platform} />
            <div className="cell-sub">{app?.name || r.appId}</div>
          </div>
        );
      },
    },
    { title: '版本', key: 'version', render: (_, r) => <Tag color="processing">v{r.version}</Tag> },
    { title: '环境', key: 'env', render: (_, r) => <EnvTag env={r.env} /> },
    { title: '状态', key: 'status', render: (_, r) => <StatusTag status={r.status} /> },
    {
      title: '进度',
      key: 'progress',
      width: 130,
      render: (_, r) => (
        <Progress
          percent={r.progress}
          size="small"
          strokeColor={statusColor(r.status)}
          status={r.status === 'building' ? 'active' : r.status === 'failed' ? 'exception' : undefined}
        />
      ),
    },
    { title: '负责人', key: 'owner', dataIndex: 'owner' },
    {
      title: '更新',
      key: 'updated',
      render: (_, r) => <Tooltip title={fmtFull(r.updatedAt)}><span>{relTime(r.updatedAt)}</span></Tooltip>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 120,
      render: (_, r) => {
        const next = NEXT_STATUS[r.status];
        return (
          <Space size={4} onClick={(e) => e.stopPropagation()}>
            {next && (
              <Tooltip title={STATUS_ACTION[r.status]}>
                <Button size="small" icon={<PlayCircleOutlined />} onClick={() => advance(r.id)} aria-label={STATUS_ACTION[r.status]} />
              </Tooltip>
            )}
            <Tooltip title="编辑">
              <Button size="small" icon={<EditOutlined />} onClick={() => openModal({ type: 'release', id: r.id })} aria-label="编辑发布单" />
            </Tooltip>
            <Tooltip title="删除">
              <Button size="small" danger icon={<DeleteOutlined />} onClick={() => remove([r.id])} aria-label="删除发布单" />
            </Tooltip>
          </Space>
        );
      },
    },
  ];

  return (
    <>
      <PageHead
        eyebrow="Release Orders"
        title="发布单"
        sub="创建、编辑、拖拽排序、批量操作与保存视图。所有变更即时保存，删除后可撤销。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal({ type: 'release', id: null })}>
            新建发布单
          </Button>
        }
      />

      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          style={{ width: 150 }}
          value={query.platform}
          onChange={(v) => setQuery({ platform: v })}
          options={[{ value: 'all', label: '全部平台' }, ...PLATFORMS.map((p) => ({ value: p, label: platformLabel(p) }))]}
        />
        <Select
          style={{ width: 150 }}
          value={query.status}
          onChange={(v) => setQuery({ status: v })}
          options={[
            { value: 'all', label: '全部状态' },
            { value: 'active', label: '进行中' },
            ...STATUSES.map((s) => ({ value: s, label: statusLabel(s) })),
          ]}
        />
        <Select
          style={{ width: 150 }}
          value={query.env}
          onChange={(v) => setQuery({ env: v })}
          options={[{ value: 'all', label: '全部环境' }, ...ENVS.map((e) => ({ value: e, label: envLabel(e) }))]}
        />
        <Select
          style={{ width: 150 }}
          value={query.sort}
          onChange={(v) => setQuery({ sort: v })}
          options={SORTS.map(([value, label]) => ({ value, label }))}
        />
        <Button icon={<SaveOutlined />} onClick={() => openModal({ type: 'savedView' })}>
          保存视图
        </Button>
      </Space>

      <Space wrap style={{ marginBottom: 16 }} align="center">
        <Text type="secondary" strong style={{ fontSize: 12 }}>Saved views</Text>
        {state.savedViews.length === 0 && <Text type="secondary" style={{ fontSize: 12 }}>暂无保存视图</Text>}
        {state.savedViews.map((v) => {
          const active = JSON.stringify(v.query) === JSON.stringify(query);
          return (
            <Tag
              key={v.id}
              color={active ? 'processing' : 'default'}
              icon={<EyeOutlined />}
              closable
              style={{ cursor: 'pointer' }}
              onClick={() => applyView(v.id)}
              onClose={(e) => {
                e.preventDefault();
                dispatch({ type: 'DELETE_VIEW', id: v.id });
              }}
            >
              {v.name}
            </Tag>
          );
        })}
      </Space>

      {selection.length > 0 && (
        <Card size="small" style={{ marginBottom: 14, borderColor: 'var(--accent)' }}>
          <Space wrap>
            <Text>已选择 <Text strong style={{ color: 'var(--accent)' }}>{selection.length}</Text> 项</Text>
            <Button size="small" icon={<PlayCircleOutlined />} onClick={onBulkAdvance}>批量推进</Button>
            <Button size="small" danger icon={<DeleteOutlined />} onClick={() => remove(selection)}>批量删除</Button>
            <Button size="small" type="text" onClick={clearSelection}>取消选择</Button>
          </Space>
        </Card>
      )}

      <Table<Release>
        rowKey="id"
        size="middle"
        columns={columns}
        dataSource={data}
        pagination={false}
        scroll={{ x: 900 }}
        components={{ body: { row: DraggableRow } }}
        rowSelection={{ selectedRowKeys: selection, onChange: (keys) => setSelection(keys as string[]) }}
        onRow={(record) => ({
          draggable: true,
          className: `${dragId === record.id ? 'dragging' : ''} ${overId === record.id ? 'drag-over' : ''}`,
          style: { cursor: 'pointer' },
          onClick: (e) => {
            if ((e.target as HTMLElement).closest('button, input, a, .ant-table-selection-column')) return;
            openDrawer(record.id);
          },
          onDragStart: (e) => {
            setDragId(record.id);
            e.dataTransfer.effectAllowed = 'move';
            e.dataTransfer.setData('text/plain', record.id);
          },
          onDragOver: (e) => {
            if (dragId && dragId !== record.id) {
              e.preventDefault();
              e.dataTransfer.dropEffect = 'move';
              setOverId(record.id);
            }
          },
          onDragLeave: () => setOverId((cur) => (cur === record.id ? null : cur)),
          onDrop: (e) => {
            e.preventDefault();
            if (dragId) {
              setQuery({ sort: 'manual' });
              dispatch({ type: 'REORDER', sourceId: dragId, targetId: record.id });
            }
            setDragId(null);
            setOverId(null);
          },
          onDragEnd: () => {
            setDragId(null);
            setOverId(null);
          },
        })}
        locale={{ emptyText: '没有匹配的发布单，调整筛选条件或新建一个。' }}
      />
      <Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
        显示 {data.length} / {state.releases.length} 个发布单 · 拖拽行可调整手动优先级
      </Text>
    </>
  );
}