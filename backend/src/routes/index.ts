import type { FastifyPluginAsync } from 'fastify';
import authRoutes from './auth.js';
import componentsRoutes from './components.js';
import requestsRoutes from './requests.js';
import notificationsRoutes from './notifications.js';
import adminRoutes from './admin.js';
import imagesRoutes from './images.js';
import paymentsRoutes from './payments.js';

const routes: FastifyPluginAsync = async (app) => {
  await app.register(authRoutes, { prefix: '/auth' });
  await app.register(componentsRoutes, { prefix: '/components' });
  await app.register(requestsRoutes);
  await app.register(notificationsRoutes);
  await app.register(adminRoutes, { prefix: '/admin' });
  await app.register(imagesRoutes, { prefix: '/components' });
  await app.register(paymentsRoutes, { prefix: '/payments' });
};

export default routes;
