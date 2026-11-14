import { useMemo, useState, type HTMLAttributes } from 'react';
import { App, Button, Card, Progress, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
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
  filterReleases,
  fmtFull,
  relTime,
  sortReleases,
  statusColor,
  type Release,
} from '@acme/core';
import { envLabelKey, platformLabelKey, statusLabelKey } from '../labels';
import { useStore } from '../store/useStore';
import { EnvTag, PlatformTag, StatusTag } from '../components/atoms';
import { PageHead } from '../components/PageHead';
import { useDeleteReleases } from '../hooks/useReleaseActions';

const { Text } = Typography;

function DraggableRow(props: HTMLAttributes<HTMLTableRowElement>) {
  return <tr {...props} />;
}

export function Releases() {
  const { t } = useTranslation();
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
    message[count ? 'success' : 'warning'](count ? t('releases.bulk.advanced', { n: count }) : t('releases.bulk.nothingToAdvance'));
  };

  const columns: ColumnsType<Release> = [
    {
      title: t('releases.columns.dragTitle'),
      key: 'drag',
      width: 34,
      render: () => (
        <span className="drag-handle" title={t('releases.columns.dragHint')}>
          <HolderOutlined />
        </span>
      ),
    },
    {
      title: t('releases.columns.release'),
      key: 'title',
      render: (_, r) => (
        <div>
          <Text strong>{r.title}</Text>
          <div className="cell-sub">{r.id} · P{r.priority}</div>
        </div>
      ),
    },
    {
      title: t('releases.columns.app'),
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
    { title: t('releases.columns.version'), key: 'version', render: (_, r) => <Tag color="processing">v{r.version}</Tag> },
    { title: t('releases.columns.env'), key: 'env', render: (_, r) => <EnvTag env={r.env} /> },
    { title: t('releases.columns.status'), key: 'status', render: (_, r) => <StatusTag status={r.status} /> },
    {
      title: t('releases.columns.progress'),
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
    { title: t('releases.columns.owner'), key: 'owner', dataIndex: 'owner' },
    {
      title: t('releases.columns.updated'),
      key: 'updated',
      render: (_, r) => <Tooltip title={fmtFull(r.updatedAt)}><span>{relTime(r.updatedAt)}</span></Tooltip>,
    },
    {
      title: t('releases.columns.actions'),
      key: 'actions',
      width: 120,
      render: (_, r) => {
        const next = NEXT_STATUS[r.status];
        return (
          <Space size={4} onClick={(e) => e.stopPropagation()}>
            {next && (
              <Tooltip title={t(`releases.action.${r.status}`)}>
                <Button size="small" icon={<PlayCircleOutlined />} onClick={() => advance(r.id)} aria-label={t(`releases.action.${r.status}`)} />
              </Tooltip>
            )}
            <Tooltip title={t('releases.row.edit')}>
              <Button size="small" icon={<EditOutlined />} onClick={() => openModal({ type: 'release', id: r.id })} aria-label={t('releases.row.editRelease')} />
            </Tooltip>
            <Tooltip title={t('releases.row.delete')}>
              <Button size="small" danger icon={<DeleteOutlined />} onClick={() => remove([r.id])} aria-label={t('releases.row.deleteRelease')} />
            </Tooltip>
          </Space>
        );
      },
    },
  ];

  return (
    <>
      <PageHead
        eyebrow={t('releases.head.eyebrow')}
        title={t('releases.head.title')}
        sub={t('releases.head.sub')}
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal({ type: 'release', id: null })}>
            {t('releases.head.create')}
          </Button>
        }
      />

      <Space wrap style={{ marginBottom: 16 }}>
        <Select
          style={{ width: 150 }}
          value={query.platform}
          onChange={(v) => setQuery({ platform: v })}
          options={[{ value: 'all', label: t('releases.filters.allPlatforms') }, ...PLATFORMS.map((p) => ({ value: p, label: t(platformLabelKey(p)) }))]}
        />
        <Select
          style={{ width: 150 }}
          value={query.status}
          onChange={(v) => setQuery({ status: v })}
          options={[
            { value: 'all', label: t('releases.filters.allStatuses') },
            { value: 'active', label: t('releases.filters.active') },
            ...STATUSES.map((s) => ({ value: s, label: t(statusLabelKey(s)) })),
          ]}
        />
        <Select
          style={{ width: 150 }}
          value={query.env}
          onChange={(v) => setQuery({ env: v })}
          options={[{ value: 'all', label: t('releases.filters.allEnvs') }, ...ENVS.map((e) => ({ value: e, label: t(envLabelKey(e)) }))]}
        />
        <Select
          style={{ width: 150 }}
          value={query.sort}
          onChange={(v) => setQuery({ sort: v })}
          options={SORTS.map(([value]) => ({ value, label: t(`releases.sort.${value}`) }))}
        />
        <Button icon={<SaveOutlined />} onClick={() => openModal({ type: 'savedView' })}>
          {t('releases.filters.saveView')}
        </Button>
      </Space>

      <Space wrap style={{ marginBottom: 16 }} align="center">
        <Text type="secondary" strong style={{ fontSize: 12 }}>{t('releases.savedViews.label')}</Text>
        {state.savedViews.length === 0 && <Text type="secondary" style={{ fontSize: 12 }}>{t('releases.savedViews.empty')}</Text>}
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
            <Text>{t('releases.bulk.selected')} <Text strong style={{ color: 'var(--accent)' }}>{selection.length}</Text> {t('releases.bulk.selectedSuffix')}</Text>
            <Button size="small" icon={<PlayCircleOutlined />} onClick={onBulkAdvance}>{t('releases.bulk.advance')}</Button>
            <Button size="small" danger icon={<DeleteOutlined />} onClick={() => remove(selection)}>{t('releases.bulk.delete')}</Button>
            <Button size="small" type="text" onClick={clearSelection}>{t('releases.bulk.clear')}</Button>
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
        locale={{ emptyText: t('releases.empty') }}
      />
      <Text type="secondary" style={{ display: 'block', marginTop: 12, fontSize: 12 }}>
        {t('releases.summary', { shown: data.length, total: state.releases.length })}
      </Text>
    </>
  );
}
