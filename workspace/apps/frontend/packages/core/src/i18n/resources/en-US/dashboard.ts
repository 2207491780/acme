/** en-US dashboard resource pack. */
export const dashboard = {
  hero: {
    eyebrow: 'ACME · Frontend Delivery',
    title: 'Multi-client delivery on one screen',
    desc: 'Manage releases across Web, Electron desktop and mobile in one place. Desktop reuses the web build, mobile shares the business layer — track everything from build, test, approval to release.',
  },
  stats: {
    active: 'In progress',
    approved: 'Ready to release',
    publishedToday: 'Released today',
    failed: 'Failed, needs attention',
  },
  stages: {
    draft: 'Draft',
    building: 'Building',
    testing: 'Testing',
    approved: 'Approval',
    published: 'Released',
  },
  cards: {
    pipeline: 'Delivery pipeline',
    pipelineTotal: '{{total}} releases in total',
    statusDistribution: 'Status distribution',
    trendChart: 'Release trend (last 7 days)',
    distributionChart: 'Platform distribution',
    recent: 'Recently updated',
    activity: 'Activity',
    recentAll: 'All',
  },
  actions: {
    createRelease: 'New release',
    viewAll: 'View all releases',
    apps: 'App structure',
    files: 'Files',
  },
  noRelease: 'No releases yet',
  mobile: {
    heroDesc: 'Manage releases for web, Electron desktop and mobile in one place. Mobile shares the @acme/core business layer — track builds, testing, approval and publishing end to end.',
    recentActivity: 'Recent activity',
  },
};
