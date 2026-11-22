import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type ReactElement } from 'react';
import { App, Button, Space, Tag, Tooltip, Typography } from 'antd';
import {
  CheckCircleOutlined, CloudUploadOutlined, DownloadOutlined, ImportOutlined,
  ProfileOutlined, SaveOutlined, UndoOutlined,
} from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
import type { Graph } from '@antv/x6';
import {
  ApiError, Auth, BpmnXmlError, systemErrorMessage,
  workflowApi, WORKFLOW_ERROR_CODES, workflowValidationErrorsOf,
  resolveCanvasPalette, resolveThemeMode, SPACING, FONT_SIZE, RADII,
  type BpmnElementType, type BpmnModel, type ProcessDefinitionView,
} from '@acme/core';
import { useStore } from '../../store/useStore';
import { useSystemPrefersDark } from '../../hooks/useSystemPrefersDark';
import { download } from '../../utils/dom';
import { Palette, BPMN_DND_MIME } from './Palette';
import { PropertyPanel } from './PropertyPanel';
import { ValidationPanel, type ValidationItem, type ValidationStatus } from './ValidationPanel';
import { ImportXmlModal } from './ImportXmlModal';
import { VersionDiffModal } from './VersionDiffModal';
// 画布装配共享工厂（REQ-20261122-102730 契约⑥）：形状注册/装配/主题/模型同步单源
import { applyCanvasTheme, createBpmnCanvas, syncModelToGraph } from './canvasFactory';
import {
  autoLayout, buildSavePayload, canConnect, connect, createNodeModel,
  emptyModel, importModel, nodeSummary, parseLayoutJson, removeEdge, removeNode, runValidation,
  updateEdge, updateNode, type ConnectRejectReason, type Selection,
} from './designerModel';

/** 连线拒绝原因 → i18n 键。 */
function rejectMessageKey(reason: ConnectRejectReason | undefined): string {
  return `workflow.designer.connect.${reason ?? 'duplicate'}`;
}

const { Text } = Typography;

/** 解析 /workflow/designer/:definitionId?（TabKeepAlive 容器外渲染，路由参数自解析）。 */
function parseDefinitionId(pathname: string): string | null {
  const match = /^\/workflow\/designer(?:\/([^/]+))?$/.exec(pathname);
  return match?.[1] ?? null;
}

/**
 * 流程设计器页（REQ-20261122-023441 BPM-2）：
 * Palette 左拖（HTML5 dnd）/ 画布网格+对齐+框选+minimap / 节点 React 组件 / 正交连线+validateConnection
 * 白名单 / PropertyPanel 防抖回写 / 工具栏=保存草稿(79)/校验/导入/导出/版本对比/部署(79)。
 * X6 画布交互归浏览器实测（确认⑧）；本页承载装配与联动。
 */
export function DesignerPage(): ReactElement {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const location = useLocation();
  const definitionId = parseDefinitionId(location.pathname);
  const preferences = useStore((s) => s.state.settings.preferences);
  const systemPrefersDark = useSystemPrefersDark();
  const resolvedMode = resolveThemeMode(preferences.mode, systemPrefersDark);
  const canvas = useMemo(() => resolveCanvasPalette(resolvedMode), [resolvedMode]);

  const containerRef = useRef<HTMLDivElement | null>(null);
  const minimapRef = useRef<HTMLDivElement | null>(null);
  const graphRef = useRef<Graph | null>(null);
  const positionsRef = useRef(new Map<string, { x: number; y: number }>());
  const modelRef = useRef<BpmnModel>(emptyModel());
  const dropTypeRef = useRef<BpmnElementType | null>(null);

  const [model, setModel] = useState<BpmnModel>(() => modelRef.current);
  const [selection, setSelection] = useState<Selection>(null);
  const [dirty, setDirty] = useState(false);
  const [definition, setDefinition] = useState<ProcessDefinitionView | null>(null);
  const [validationStatus, setValidationStatus] = useState<ValidationStatus>('idle');
  const [validationItems, setValidationItems] = useState<ValidationItem[]>([]);
  const [savedXml, setSavedXml] = useState('');
  const [importOpen, setImportOpen] = useState(false);
  const [diffOpen, setDiffOpen] = useState(false);

  const applyModel = useCallback((next: BpmnModel) => {
    modelRef.current = next;
    setModel(next);
    setDirty(true);
  }, []);

  // —— 画布装配（挂载一次；经共享工厂 canvasFactory，契约⑥） ——
  useEffect(() => {
    if (!containerRef.current) return undefined;
    const graph = createBpmnCanvas({
      container: containerRef.current,
      minimapContainer: minimapRef.current,
      palette: canvas,
      handlers: {
        onBlankClick: () => setSelection(null),
        onCellClick: (id, isEdge) => setSelection(isEdge ? { kind: 'edge', id } : { kind: 'node', id }),
        onNodeMoved: (id, position) => positionsRef.current.set(id, position),
        validateConnection: (sourceId, targetId) => canConnect(modelRef.current, sourceId, targetId).ok,
        createEdge: () => graphRef.current?.createEdge({ shape: 'edge' }) ?? null,
        onEdgeConnected: (sourceId, targetId) => {
          const verdict = canConnect(modelRef.current, sourceId, targetId);
          if (!verdict.ok) {
            message.warning(t(rejectMessageKey(verdict.reason)));
            return 'remove';
          }
          const result = connect(modelRef.current, sourceId, targetId);
          if (result.edge) {
            // 临时边（X6 生成 id）由工厂移除，模型同步 effect 以模型 edge id+统一 attrs 回填
            applyModel(result.model);
            return 'remove';
          }
          return 'keep';
        },
      },
    });
    graphRef.current = graph;
    return () => {
      graph.dispose();
      graphRef.current = null;
    };
    // 仅挂载一次装配；主题变化由下方 effect 调整网格/背景
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 主题变化 → 网格/背景/节点配色令牌刷新（确认⑦）
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;
    applyCanvasTheme(graph, canvas);
    for (const node of modelRef.current.nodes) {
      const cell = graph.getCellById(node.id);
      if (cell) {
        cell.setData({
          nodeFill: canvas.nodeFill,
          nodeBorder: canvas.nodeBorder,
          nodeText: canvas.nodeText,
          nodeText2: canvas.nodeText2,
          dark: resolvedMode === 'dark',
        }, { overwrite: true });
      }
    }
  }, [canvas, resolvedMode]);

  // 模型 → 图同步（节点增删/数据刷新/连线增删；共享工厂单源）
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;
    syncModelToGraph(graph, model, positionsRef.current, canvas, resolvedMode === 'dark', {
      nameOf: (node) => node.name || t(`workflow.designer.palette.items.${node.type}`),
      summaryOf: nodeSummary,
    });
  }, [model, canvas, resolvedMode, t]);

  // —— 打开定义（definitionId 变化=同签页内路由替换，不开新签） ——
  useEffect(() => {
    const graph = graphRef.current;
    setSelection(null);
    setValidationStatus('idle');
    setValidationItems([]);
    setDirty(false);
    if (!definitionId) {
      // AC-1：新建定义跳转设计器空画布（StartEvent 预置）
      const blank = emptyModel();
      modelRef.current = blank;
      setModel(blank);
      setSavedXml('');
      setDefinition(null);
      positionsRef.current = autoLayout(blank);
      if (graph) graph.fromJSON({ cells: [] });
      return;
    }
    let cancelled = false;
    workflowApi.get(definitionId)
      .then((view) => {
        if (cancelled) return;
        setDefinition(view);
        const parsed = view.bpmnXml ? importModel(view.bpmnXml) : emptyModel();
        const layout = parseLayoutJson(view.layoutJson);
        positionsRef.current = layout
          ? new Map(layout.nodes.map((point) => [point.id, { x: point.x, y: point.y }]))
          : autoLayout(parsed);
        modelRef.current = parsed;
        setModel(parsed);
        setSavedXml(view.bpmnXml ?? '');
        if (graph) graph.fromJSON({ cells: [] });
      })
      .catch((ex: unknown) => {
        if (cancelled) return;
        message.error(systemErrorMessage(ex, t('workflow.list.errors.loadFailed')));
      });
    return () => {
      cancelled = true;
    };
    // graph 装配仅挂载一次；重建画布经 fromJSON 清空后由模型同步 effect 回填
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [definitionId]);

  // —— 动作 ——
  const highlightErrors = useCallback((items: ValidationItem[]) => {
    const graph = graphRef.current;
    const errorIds = new Set(items.map((item) => item.nodeId).filter((id): id is string => id !== null));
    for (const node of modelRef.current.nodes) {
      const cell = graph?.getCellById(node.id);
      cell?.setData({ error: errorIds.has(node.id) }, { overwrite: false });
    }
  }, []);

  const handleValidate = () => {
    const issues = runValidation(modelRef.current);
    setValidationItems(issues.map((item) => ({ nodeId: item.nodeId, message: item.message })));
    setValidationStatus(issues.length === 0 ? 'pass' : 'fail');
    highlightErrors(issues.map((item) => ({ nodeId: item.nodeId, message: item.message })));
    if (issues.length === 0) {
      message.success(t('workflow.designer.toast.validatePass'));
    }
  };

  const handleLocate = (nodeId: string) => {
    const graph = graphRef.current;
    const cell = graph?.getCellById(nodeId);
    if (!graph || !cell) return;
    graph.centerCell(cell);
    highlightErrors([{ nodeId, message: '' }]);
    setSelection({ kind: 'node', id: nodeId });
  };

  const handleSave = async () => {
    if (!definitionId) return;
    if (definition && definition.status !== 'DRAFT') {
      message.warning(t('workflow.designer.toast.onlyDraft'));
      return;
    }
    const payload = buildSavePayload(modelRef.current, positionsRef.current);
    try {
      const saved = await workflowApi.saveXml(definitionId, payload);
      setDefinition(saved);
      setSavedXml(payload.bpmnXml);
      setDirty(false);
      message.success(payload.layoutJson ? t('workflow.designer.toast.savedWithLayout') : t('workflow.designer.toast.saved'));
    } catch (ex: unknown) {
      if (ex instanceof ApiError && ex.code === WORKFLOW_ERROR_CODES.VALIDATION_FAILED) {
        const details = workflowValidationErrorsOf(ex);
        setValidationItems(details.map((item) => ({ nodeId: item.nodeId ?? null, message: item.message })));
        setValidationStatus('backend');
        highlightErrors(details.map((item) => ({ nodeId: item.nodeId ?? null, message: item.message })));
      }
      message.error(systemErrorMessage(ex, t('workflow.designer.toast.saveFailed')));
    }
  };

  const handleDeploy = async () => {
    if (!definitionId) return;
    try {
      const deployed = await workflowApi.deploy(definitionId);
      setDefinition(deployed);
      message.success(t('workflow.designer.toast.deployed'));
    } catch (ex: unknown) {
      if (ex instanceof ApiError && ex.code === WORKFLOW_ERROR_CODES.VALIDATION_FAILED) {
        const details = workflowValidationErrorsOf(ex);
        setValidationItems(details.map((item) => ({ nodeId: item.nodeId ?? null, message: item.message })));
        setValidationStatus('backend');
        highlightErrors(details.map((item) => ({ nodeId: item.nodeId ?? null, message: item.message })));
      }
      message.error(systemErrorMessage(ex, t('workflow.designer.toast.deployFailed')));
    }
  };

  const handleExport = () => {
    const payload = buildSavePayload(modelRef.current, positionsRef.current);
    download(`${definition?.key ?? 'process'}.bpmn.xml`, payload.bpmnXml, 'application/xml');
    message.success(t('workflow.designer.toast.exported'));
  };

  const handleImport = (xml: string) => {
    try {
      const parsed = importModel(xml);
      positionsRef.current = autoLayout(parsed);
      modelRef.current = parsed;
      setModel(parsed);
      setImportOpen(false);
      setDirty(true);
      setValidationStatus('idle');
      message.success(t('workflow.designer.toast.imported'));
    } catch (ex: unknown) {
      if (ex instanceof BpmnXmlError) {
        message.error(t(ex.messageKey, { element: ex.element ?? '' }));
      } else {
        message.error(t('workflow.designer.toast.importFailed'));
      }
    }
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    const type = dropTypeRef.current ?? (event.dataTransfer.getData(BPMN_DND_MIME) as BpmnElementType | '');
    dropTypeRef.current = null;
    if (!type || !modelRef.current) return;
    const graph = graphRef.current;
    if (!graph) return;
    const nodeModel = createNodeModel(type);
    const point = graph.clientToLocal(event.clientX, event.clientY);
    positionsRef.current.set(nodeModel.id, { x: point.x, y: point.y });
    applyModel({ nodes: [...modelRef.current.nodes, nodeModel], edges: modelRef.current.edges });
    setSelection({ kind: 'node', id: nodeModel.id });
  };

  const handleNodeChange = (id: string, patch: Partial<BpmnModel['nodes'][number]>) => {
    applyModel(updateNode(modelRef.current, id, patch));
  };

  const handleEdgeChange = (id: string, patch: Partial<BpmnModel['edges'][number]>) => {
    applyModel(updateEdge(modelRef.current, id, patch));
  };

  const handleDeleteNode = (id: string) => {
    applyModel(removeNode(modelRef.current, id));
    setSelection(null);
  };

  const handleDeleteEdge = (id: string) => {
    applyModel(removeEdge(modelRef.current, id));
    setSelection(null);
  };

  const selectedNode = selection?.kind === 'node' ? model.nodes.find((node) => node.id === selection.id) ?? null : null;
  const selectedEdge = selection?.kind === 'edge' ? model.edges.find((edge) => edge.id === selection.id) ?? null : null;
  const attachableNodes = model.nodes.filter(
    (node) => node.type === 'UserTask' || node.type === 'ServiceTask' || node.type === 'EmbeddedSubProcess',
  );

  const statusTag = definition ? (
    <Tag color={definition.status === 'DEPLOYED' ? 'success' : definition.status === 'DISABLED' ? 'error' : 'default'}>
      {t(`workflow.list.status.${definition.status}`)}
    </Tag>
  ) : (
    <Tag>{t('workflow.designer.title')}</Tag>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: SPACING.md, height: 'calc(100vh - 160px)' }}>
      {/* 工具栏：保存草稿(79)/校验/导入/导出/版本对比/部署(79) */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: SPACING.sm,
          padding: `${SPACING.sm}px ${SPACING.md}px`,
          background: 'var(--surface)',
          border: '1px solid var(--border)',
          borderRadius: RADII.sm,
        }}
      >
        {statusTag}
        {dirty && <Text type="warning" style={{ fontSize: FONT_SIZE.small }}>●</Text>}
        <Space wrap>
          {definitionId ? (
            <Auth code="workflow:definition:deploy">
              <Button type="primary" icon={<SaveOutlined />} onClick={() => void handleSave()}>
                {t('workflow.designer.toolbar.save')}
              </Button>
            </Auth>
          ) : null}
          <Button icon={<CheckCircleOutlined />} onClick={handleValidate}>{t('workflow.designer.toolbar.validate')}</Button>
          <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>{t('workflow.designer.toolbar.import')}</Button>
          <Button icon={<DownloadOutlined />} onClick={handleExport}>{t('workflow.designer.toolbar.export')}</Button>
          <Button icon={<ProfileOutlined />} onClick={() => setDiffOpen(true)}>{t('workflow.designer.toolbar.diff')}</Button>
          {definitionId ? (
            <Auth code="workflow:definition:deploy">
              <Tooltip title={t('workflow.list.confirm.deploy')}>
                <Button icon={<CloudUploadOutlined />} onClick={() => void handleDeploy()} disabled={definition?.status !== 'DRAFT'}>
                  {t('workflow.designer.toolbar.deploy')}
                </Button>
              </Tooltip>
            </Auth>
          ) : null}
          <Button icon={<UndoOutlined />} onClick={() => setModel(modelRef.current)} aria-label={t('workflow.designer.toolbar.refresh')} />
        </Space>
      </div>

      <div style={{ display: 'flex', flex: 1, minHeight: 0, gap: SPACING.md }}>
        <Palette />
        {/* 画布：网格+对齐+框选+minimap；onDrop 承接 Palette HTML5 拖拽 */}
        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          <div
            ref={containerRef}
            data-testid="bpmn-canvas"
            style={{ flex: 1, border: '1px solid var(--border)', borderRadius: RADII.sm, overflow: 'hidden' }}
            onDragOver={(event) => {
              event.preventDefault();
              event.dataTransfer.dropEffect = 'copy';
            }}
            onDrop={handleDrop}
          />
          <ValidationPanel status={validationStatus} items={validationItems} onLocate={handleLocate} />
        </div>
        {/* 右栏：属性面板 + minimap */}
        <div
          style={{
            width: 300,
            flexShrink: 0,
            display: 'flex',
            flexDirection: 'column',
            gap: SPACING.md,
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: RADII.sm,
          }}
        >
          <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
            <PropertyPanel
              selection={selection}
              node={selectedNode}
              edge={selectedEdge}
              attachableNodes={attachableNodes}
              onNodeChange={handleNodeChange}
              onEdgeChange={handleEdgeChange}
              onDeleteNode={handleDeleteNode}
              onDeleteEdge={handleDeleteEdge}
            />
          </div>
          <div style={{ borderTop: '1px solid var(--border)', padding: SPACING.sm, display: 'flex', justifyContent: 'center' }}>
            <div ref={minimapRef} data-testid="bpmn-minimap" style={{ width: 180, height: 120 }} />
          </div>
        </div>
      </div>

      <ImportXmlModal open={importOpen} onClose={() => setImportOpen(false)} onImport={handleImport} />
      <VersionDiffModal
        open={diffOpen}
        onClose={() => setDiffOpen(false)}
        leftXml={buildSavePayload(modelRef.current, positionsRef.current).bpmnXml}
        leftLabel={t('workflow.designer.diff.leftLabel')}
        rightXml={savedXml}
        rightLabel={t('workflow.designer.diff.rightLabel')}
      />
    </div>
  );
}
