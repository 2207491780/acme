import { useEffect, useRef } from 'react';
import { Button, Checkbox, Form, Input, Popconfirm, Select, Space, Switch, Typography } from 'antd';
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { BpmnEdgeModel, BpmnNodeModel } from '@acme/core';
import type { Selection } from './designerModel';

/**
 * 属性面板（REQ-20261122-023441 AC-5）：AntD 表单按元素类型渲染，编辑经 300ms 防抖回写模型。
 * 无 X6 依赖（面板联动为 jsdom 测试面）；Form 以选中对象为 key 重挂载复位。
 */

const COMMIT_DEBOUNCE_MS = 300;

const { Text } = Typography;

export interface PropertyPanelProps {
  selection: Selection;
  node: BpmnNodeModel | null;
  edge: BpmnEdgeModel | null;
  /** 边界事件可依附目标（UserTask/ServiceTask/EmbeddedSubProcess）。 */
  attachableNodes: BpmnNodeModel[];
  onNodeChange: (id: string, patch: Partial<BpmnNodeModel>) => void;
  onEdgeChange: (id: string, patch: Partial<BpmnEdgeModel>) => void;
  onDeleteNode: (id: string) => void;
  onDeleteEdge: (id: string) => void;
}

interface NodeFormValues {
  name?: string;
  assignee?: string;
  candidateRolesCsv?: string;
  candidateDeptsCsv?: string;
  candidateUsersCsv?: string;
  interrupting?: boolean;
  attachedToRef?: string | null;
  serviceRef?: string;
  callbackPath?: string;
  retryPolicy?: string;
  listeners?: Array<{ event?: 'before' | 'after'; ref?: string }>;
}

interface EdgeFormValues {
  name?: string;
  condition?: string;
  isDefault?: boolean;
}

/** csv 串 → 数组（空串→undefined，回写面保持「未设置」语义）。 */
function csvToArray(value: string | undefined): string[] | undefined {
  if (value === undefined) return undefined;
  const items = value.split(',').map((item) => item.trim()).filter((item) => item !== '');
  return items.length > 0 ? items : undefined;
}

function arrayToCsv(value: string[] | undefined): string {
  return value?.join(',') ?? '';
}

export function PropertyPanel({
  selection,
  node,
  edge,
  attachableNodes,
  onNodeChange,
  onEdgeChange,
  onDeleteNode,
  onDeleteEdge,
}: PropertyPanelProps) {
  const { t } = useTranslation();
  const [form] = Form.useForm<NodeFormValues>();
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 选中对象变化即复位表单值（Form key 重挂载已复位，防御性同步保留）
  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, []);

  /** 节点表单 → 模型 patch（csv/监听器归一化在此收敛）。 */
  const commitNode = () => {
    if (!node) return;
    const values = form.getFieldsValue();
    const patch: Partial<BpmnNodeModel> = {};
    if (values.name !== undefined) patch.name = values.name.trim();
    if (node.type === 'UserTask') {
      patch.assignee = values.assignee?.trim() || undefined;
      patch.candidateRoles = csvToArray(values.candidateRolesCsv);
      patch.candidateDepts = csvToArray(values.candidateDeptsCsv);
      patch.candidateUsers = csvToArray(values.candidateUsersCsv);
    }
    if (node.type === 'ServiceTask') {
      patch.serviceRef = values.serviceRef?.trim() || undefined;
      patch.callbackPath = values.callbackPath?.trim() || undefined;
      patch.retryPolicy = values.retryPolicy?.trim() || undefined;
    }
    if (node.type === 'BoundaryEvent') {
      patch.interrupting = values.interrupting !== false;
      patch.attachedToRef = values.attachedToRef ?? null;
    }
    patch.executionListeners = (values.listeners ?? [])
      .filter((listener) => typeof listener.ref === 'string' && listener.ref.trim() !== '')
      .map((listener) => ({
        event: listener.event === 'after' ? ('after' as const) : ('before' as const),
        ref: (listener.ref ?? '').trim(),
      }));
    onNodeChange(node.id, patch);
  };

  /** 防抖回写（AC-5：输入停顿 300ms 后落模型，避免逐字符触发画布/校验刷新）。 */
  const scheduleNodeCommit = () => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
    }
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      commitNode();
    }, COMMIT_DEBOUNCE_MS);
  };

  const scheduleEdgeCommit = () => {
    if (!edge) return;
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
    }
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      if (!edge) return;
      const values = form.getFieldsValue() as EdgeFormValues;
      onEdgeChange(edge.id, {
        name: values.name?.trim() || undefined,
        condition: values.condition?.trim() || undefined,
        isDefault: values.isDefault === true,
      });
    }, COMMIT_DEBOUNCE_MS);
  };

  if (selection?.kind === 'edge' && edge) {
    return (
      <div data-testid="property-panel" style={{ padding: 12 }}>
        <Text strong style={{ display: 'block', marginBottom: 12 }}>{t('workflow.designer.property.edge.title')}</Text>
        <Form
          form={form}
          layout="vertical"
          key={`edge-${edge.id}`}
          initialValues={{ name: edge.name ?? '', condition: edge.condition ?? '', isDefault: edge.isDefault === true }}
          onValuesChange={scheduleEdgeCommit}
        >
          <Form.Item name="name" label={t('workflow.designer.property.edge.name')}>
            <Input allowClear />
          </Form.Item>
          <Form.Item name="condition" label={t('workflow.designer.property.edge.condition')}>
            <Input allowClear placeholder={t('workflow.designer.property.edge.conditionPlaceholder')} style={{ fontFamily: 'var(--mono)' }} />
          </Form.Item>
          <Form.Item name="isDefault" valuePropName="checked">
            <Checkbox>{t('workflow.designer.property.edge.defaultFlow')}</Checkbox>
          </Form.Item>
        </Form>
        <Popconfirm title={t('workflow.designer.property.common.deleteEdge')} onConfirm={() => onDeleteEdge(edge.id)}>
          <Button danger size="small" icon={<DeleteOutlined />}>{t('workflow.designer.property.common.deleteEdge')}</Button>
        </Popconfirm>
      </div>
    );
  }

  if (selection?.kind === 'node' && node) {
    return (
      <div data-testid="property-panel" style={{ padding: 12, overflowY: 'auto' }}>
        <Text strong style={{ display: 'block', marginBottom: 12 }}>{t('workflow.designer.property.title')}</Text>
        <Form
          form={form}
          layout="vertical"
          key={`node-${node.id}`}
          initialValues={{
            name: node.name ?? '',
            assignee: node.assignee ?? '',
            candidateRolesCsv: arrayToCsv(node.candidateRoles),
            candidateDeptsCsv: arrayToCsv(node.candidateDepts),
            candidateUsersCsv: arrayToCsv(node.candidateUsers),
            interrupting: node.interrupting !== false,
            attachedToRef: node.attachedToRef ?? null,
            serviceRef: node.serviceRef ?? '',
            callbackPath: node.callbackPath ?? '',
            retryPolicy: node.retryPolicy ?? '',
            listeners: node.executionListeners.map((listener) => ({ event: listener.event, ref: listener.ref })),
          }}
          onValuesChange={scheduleNodeCommit}
        >
          <Form.Item name="name" label={t('workflow.designer.property.common.name')}>
            <Input allowClear />
          </Form.Item>

          {node.type === 'UserTask' && (
            <>
              <Form.Item name="assignee" label={t('workflow.designer.property.userTask.assignee')}>
                <Input allowClear placeholder={t('workflow.designer.property.userTask.assigneePlaceholder')} style={{ fontFamily: 'var(--mono)' }} />
              </Form.Item>
              <Form.Item name="candidateRolesCsv" label={t('workflow.designer.property.userTask.candidateRoles')} extra={t('workflow.designer.property.userTask.csvHint')}>
                <Input allowClear />
              </Form.Item>
              <Form.Item name="candidateDeptsCsv" label={t('workflow.designer.property.userTask.candidateDepts')}>
                <Input allowClear />
              </Form.Item>
              <Form.Item name="candidateUsersCsv" label={t('workflow.designer.property.userTask.candidateUsers')}>
                <Input allowClear />
              </Form.Item>
              <Form.Item label={t('workflow.designer.property.userTask.assigneeStrategy')}>
                <Select value="ALL" disabled options={[{ value: 'ALL', label: 'ALL' }]} />
              </Form.Item>
            </>
          )}

          {node.type === 'ServiceTask' && (
            <>
              <Form.Item name="serviceRef" label={t('workflow.designer.property.serviceTask.serviceRef')}>
                <Input allowClear style={{ fontFamily: 'var(--mono)' }} />
              </Form.Item>
              <Form.Item name="callbackPath" label={t('workflow.designer.property.serviceTask.callbackPath')}>
                <Input allowClear style={{ fontFamily: 'var(--mono)' }} />
              </Form.Item>
              <Form.Item name="retryPolicy" label={t('workflow.designer.property.serviceTask.retryPolicy')}>
                <Input allowClear style={{ fontFamily: 'var(--mono)' }} />
              </Form.Item>
            </>
          )}

          {node.type === 'BoundaryEvent' && (
            <>
              <Form.Item name="interrupting" label={t('workflow.designer.property.boundary.interrupting')} valuePropName="checked">
                <Switch />
              </Form.Item>
              <Form.Item name="attachedToRef" label={t('workflow.designer.property.boundary.attachedTo')}>
                <Select
                  allowClear
                  options={[
                    { value: null, label: t('workflow.designer.property.boundary.attachedToNone') },
                    ...attachableNodes.map((candidate) => ({ value: candidate.id, label: candidate.name || candidate.id })),
                  ]}
                />
              </Form.Item>
            </>
          )}

          <Form.Item label={t('workflow.designer.property.listeners.title')}>
            <Form.List name="listeners">
              {(fields, { add, remove }) => (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {fields.length === 0 && (
                    <Text type="secondary" style={{ fontSize: 12 }}>{t('workflow.designer.property.listeners.empty')}</Text>
                  )}
                  {fields.map((field) => (
                    <Space.Compact key={field.key} style={{ width: '100%' }}>
                      <Form.Item name={[field.name, 'event']} initialValue="before" noStyle>
                        <Select
                          style={{ width: 96 }}
                          options={[
                            { value: 'before', label: t('workflow.designer.property.listeners.before') },
                            { value: 'after', label: t('workflow.designer.property.listeners.after') },
                          ]}
                        />
                      </Form.Item>
                      <Form.Item name={[field.name, 'ref']} noStyle>
                        <Input placeholder={t('workflow.designer.property.listeners.ref')} style={{ fontFamily: 'var(--mono)' }} />
                      </Form.Item>
                      <Button icon={<DeleteOutlined />} onClick={() => remove(field.name)} aria-label={t('workflow.designer.property.listeners.remove')} />
                    </Space.Compact>
                  ))}
                  <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ event: 'before', ref: '' })} block>
                    {t('workflow.designer.property.listeners.add')}
                  </Button>
                </div>
              )}
            </Form.List>
          </Form.Item>
        </Form>
        <Popconfirm title={t('workflow.designer.property.common.deleteNode')} onConfirm={() => onDeleteNode(node.id)}>
          <Button danger size="small" icon={<DeleteOutlined />}>{t('workflow.designer.property.common.deleteNode')}</Button>
        </Popconfirm>
      </div>
    );
  }

  return (
    <div data-testid="property-panel" style={{ padding: 12 }}>
      <Text type="secondary">{t('workflow.designer.property.noSelection')}</Text>
    </div>
  );
}
