import { setupServer } from 'msw/node';
import { handlers } from './handlers';

/**
 * 测试侧 MSW server（按需启用）：用例文件经 beforeAll(server.listen)/afterAll(server.close)
 * 显式接入；默认缺省 handler 覆盖核心端点，用例可 server.use(...) 覆盖任意 handler。
 */
export const server = setupServer(...handlers);
