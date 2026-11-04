import './test-setup.js';

import { describe, test, beforeAll, afterAll } from 'bun:test';
import assert from 'node:assert/strict';
import { buildApp } from '../src/app.js';
import {
  createComponent,
  createUser,
  createRequest,
  deleteAllData,
  deleteAllRequests,
  deleteComponents,
  deleteUsers,
  findComponentById,
  ComponentCategory,
  Location,
  UserRole,
} from './helpers.js';

let app: Awaited<ReturnType<typeof buildApp>>;
let adminToken: string;
let taToken: string;
let studentToken: string;
let facultyToken: string;

let adminUserId: string;
let taUserId: string;
let studentUserId: string;
let facultyUserId: string;

beforeAll(async () => {
  app = await buildApp();

  const { hash } = await import('bcryptjs');
  const passwordHash = await hash('password123', 12);

  const adminUser = await createUser({
    email: `admin_${crypto.randomUUID()}@example.com`,
    passwordHash,
    name: 'Admin User',
    role: UserRole.ADMIN,
  });
  adminUserId = adminUser.id;
  adminToken = app.jwt.sign({ sub: adminUser.id, role: adminUser.role }, { expiresIn: '1h' });

  const taUser = await createUser({
    email: `ta_${crypto.randomUUID()}@example.com`,
    passwordHash,
    name: 'LA User',
    role: UserRole.LA,
  });
  taToken = app.jwt.sign({ sub: taUser.id, role: taUser.role }, { expiresIn: '1h' });
  taUserId = taUser.id;

  const studentUser = await createUser({
    email: `student_${crypto.randomUUID()}@example.com`,
    passwordHash,
    name: 'Student User',
    role: UserRole.STUDENT,
  });
  studentToken = app.jwt.sign({ sub: studentUser.id, role: studentUser.role }, { expiresIn: '1h' });
  studentUserId = studentUser.id;

  const facultyUser = await createUser({
    email: `faculty_${crypto.randomUUID()}@example.com`,
    passwordHash,
    name: 'Faculty User',
    role: UserRole.FACULTY,
  });
  facultyToken = app.jwt.sign({ sub: facultyUser.id, role: facultyUser.role }, { expiresIn: '1h' });
  facultyUserId = facultyUser.id;
});

afterAll(async () => {
  await deleteUsers([adminUserId, taUserId, studentUserId, facultyUserId].filter(Boolean));
  await app.close();
});

describe('Component CRUD API', () => {
  describe('GET /components - List all components', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/components',
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns 200 with empty list for STUDENT role', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/components',
        headers: {
          authorization: `Bearer ${studentToken}`,
        },
      });

      assert.equal(response.statusCode, 200);
      assert.deepEqual(response.json().components, []);
    });

    test('returns 200 with empty list for FACULTY role', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/components',
        headers: {
          authorization: `Bearer ${facultyToken}`,
        },
      });

      assert.equal(response.statusCode, 200);
      assert.deepEqual(response.json().components, []);
    });

    test('returns 200 with empty list when no components exist (ADMIN)', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/components',
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
      });

      assert.equal(response.statusCode, 200);
      assert.deepEqual(response.json().components, []);
    });

    test('returns 200 with components (LA)', async () => {
      const component1 = await createComponent({
        name: 'Resistor 10k',
        description: '10k ohm resistor',
        totalQuantity: 50,
        availableQuantity: 50,
        category: ComponentCategory.Sensors,
        location: Location.IoT_Lab,
      });

      const component2 = await createComponent({
        name: 'Arduino Uno',
        description: 'Arduino microcontroller',
        totalQuantity: 10,
        availableQuantity: 10,
        category: ComponentCategory.Microcontrollers,
        location: Location.Robo_Lab,
      });

      const response = await app.inject({
        method: 'GET',
        url: '/components',
        headers: {
          authorization: `Bearer ${taToken}`,
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.ok(Array.isArray(body.components));

      // LA should see both created components, ordered by createdAt desc
      const owned = body.components.filter(
        (c: { id: string }) => c.id === component1.id || c.id === component2.id,
      );
      assert.equal(owned.length, 2);
      assert.equal(owned[0].id, component2.id);
      assert.equal(owned[1].id, component1.id);

      // Clean up created components
      await deleteComponents([component1.id, component2.id]);
    });

    test('returns 304 Not Modified when If-Modified-Since is up to date (LA)', async () => {
      const comp = await createComponent({
        name: 'Conditional GET Component',
        description: 'For If-Modified-Since testing',
        totalQuantity: 5,
        availableQuantity: 5,
        category: ComponentCategory.Sensors,
        location: Location.IoT_Lab,
      });

      const firstResponse = await app.inject({
        method: 'GET',
        url: '/components',
        headers: {
          authorization: `Bearer ${taToken}`,
        },
      });

      assert.equal(firstResponse.statusCode, 200);
      const lastModified = firstResponse.headers['last-modified'];
      assert.ok(lastModified);

      const secondResponse = await app.inject({
        method: 'GET',
        url: '/components',
        headers: {
          authorization: `Bearer ${taToken}`,
          'if-modified-since': lastModified,
        },
      });

      assert.equal(secondResponse.statusCode, 304);

      await deleteComponents([comp.id]);
    });

    describe('GET /components/export/csv', () => {
      test('returns 401 without token', async () => {
        const response = await app.inject({
          method: 'GET',
          url: '/components/export/csv',
        });
        assert.equal(response.statusCode, 401);
      });

      test('returns 403 for STUDENT role', async () => {
        const response = await app.inject({
          method: 'GET',
          url: '/components/export/csv',
          headers: { authorization: `Bearer ${studentToken}` },
        });
        assert.equal(response.statusCode, 403);
      });

      test('returns 200 and CSV content for ADMIN', async () => {
        const csvComponent = await createComponent({
          name: 'CSV Component',
          totalQuantity: 10,
          availableQuantity: 10,
          category: ComponentCategory.Sensors,
          location: Location.IoT_Lab,
        });

        try {
          const response = await app.inject({
            method: 'GET',
            url: '/components/export/csv',
            headers: { authorization: `Bearer ${adminToken}` },
          });

          assert.equal(response.statusCode, 200);
          assert.equal(response.headers['content-type'], 'text/csv');
          assert.ok(response.payload.includes('CSV Component'));
          assert.ok(response.payload.includes('Name,Description,Category,Location'));
        } finally {
          await deleteComponents([csvComponent.id]);
        }
      });

      test('returns 200 and CSV content for FACULTY', async () => {
        const response = await app.inject({
          method: 'GET',
          url: '/components/export/csv',
          headers: { authorization: `Bearer ${facultyToken}` },
        });
        assert.equal(response.statusCode, 200);
        assert.equal(response.headers['content-type'], 'text/csv');
      });
    });

    describe('POST /components/import/csv', () => {
      test('returns 401 without token', async () => {
        const csv =
          'Name,Description,Category,Location,Total Quantity,Available Quantity\nResistor,10k ohm,Sensors,IoT Lab,50,50';
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: { 'content-type': 'text/csv' },
          body: csv,
        });
        assert.equal(response.statusCode, 401);
      });

      test('returns 403 for STUDENT role', async () => {
        const csv =
          'Name,Description,Category,Location,Total Quantity,Available Quantity\nResistor,10k ohm,Sensors,IoT Lab,50,50';
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${studentToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });
        assert.equal(response.statusCode, 403);
      });

      test('returns 403 for FACULTY role', async () => {
        const csv =
          'Name,Description,Category,Location,Total Quantity,Available Quantity\nResistor,10k ohm,Sensors,IoT Lab,50,50';
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${facultyToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });
        assert.equal(response.statusCode, 403);
      });

      test('returns 400 when CSV content is empty', async () => {
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: '',
        });
        assert.equal(response.statusCode, 400);
        assert.equal(response.json().error, 'CSV content is required');
      });

      test('returns 400 when Name column is missing', async () => {
        const csv = 'Description,Category,Location\n10k ohm,Sensors,IoT Lab';
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });
        assert.equal(response.statusCode, 400);
        assert.equal(response.json().error, "CSV must contain a 'Name' column");
      });

      test('returns 400 when data rows exceed 1000', async () => {
        const header = 'Name,Description,Category,Location,Total Quantity,Available Quantity\n';
        const rows = Array.from(
          { length: 1001 },
          (_, i) => `Component ${i},Desc,Sensors,IoT Lab,10,10`,
        ).join('\n');
        const csv = header + rows;
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });
        assert.equal(response.statusCode, 400);
        assert.ok(response.json().error.includes('cannot contain more than 1000 data rows'));
      });

      test('admin imports valid 2-row CSV', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          'Resistor 10k,10k ohm resistor,Sensors,IoT Lab,50,50',
          'Arduino Uno,Arduino microcontroller,Microcontrollers,Robo Lab,10,10',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 201);
        const body = response.json();
        assert.equal(body.imported, 2);
        assert.equal(body.components.length, 2);
        assert.equal(body.components[0].name, 'Resistor 10k');
        assert.equal(body.components[1].name, 'Arduino Uno');
        assert.equal(body.components[0].category, ComponentCategory.Sensors);
        assert.equal(body.components[0].location, Location.IoT_Lab);
        assert.equal(body.components[1].category, ComponentCategory.Microcontrollers);
        assert.equal(body.components[1].location, Location.Robo_Lab);

        // Verify components exist via GET
        const getResponse = await app.inject({
          method: 'GET',
          url: '/components',
          headers: { authorization: `Bearer ${adminToken}` },
        });
        assert.equal(getResponse.statusCode, 200);
        const components = getResponse.json().components;
        const imported = components.filter(
          (c: { name: string }) => c.name === 'Resistor 10k' || c.name === 'Arduino Uno',
        );
        assert.equal(imported.length, 2);

        await deleteComponents(body.components.map((c: { id: string }) => c.id));
      });

      test('LA can import CSV', async () => {
        const csv =
          'Name,Description,Category,Location,Total Quantity,Available Quantity\nCapacitor 100uF,Electrolytic capacitor,Actuators,VLSI Lab,20,20';
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${taToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });
        assert.equal(response.statusCode, 201);
        const body = response.json();
        assert.equal(body.imported, 1);
        assert.equal(body.components[0].name, 'Capacitor 100uF');
        await deleteComponents(body.components.map((c: { id: string }) => c.id));
      });

      test('returns 400 for invalid category with row number in details', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          'Valid Component,Description,Sensors,IoT Lab,10,10',
          'Invalid Component,Description,InvalidCategory,IoT Lab,10,10',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.equal(body.error, 'import failed');
        assert.ok(Array.isArray(body.details));
        assert.equal(body.details.length, 1);
        assert.equal(body.details[0].row, 2); // Second data row (1-based)
        assert.ok(body.details[0].message.includes('invalid category'));

        // Verify no components were created (atomic)
        const getResponse = await app.inject({
          method: 'GET',
          url: '/components',
          headers: { authorization: `Bearer ${adminToken}` },
        });
        const components = getResponse.json().components;
        const imported = components.filter(
          (c: { name: string }) => c.name === 'Valid Component' || c.name === 'Invalid Component',
        );
        assert.equal(imported.length, 0);
      });

      test('quoted field with embedded comma parses correctly', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          '"Resistor, 10k","A resistor, with comma",Sensors,IoT Lab,5,5',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 201);
        const body = response.json();
        assert.equal(body.imported, 1);
        assert.equal(body.components[0].name, 'Resistor, 10k');
        assert.equal(body.components[0].description, 'A resistor, with comma');
        await deleteComponents(body.components.map((c: { id: string }) => c.id));
      });

      test('round-trip: export CSV and re-import', async () => {
        // Create a component first
        const original = await createComponent({
          name: 'Round Trip Component',
          description: 'Test round trip',
          totalQuantity: 25,
          availableQuantity: 20,
          category: ComponentCategory.Microprocessors,
          location: Location.Robo_Lab,
          imageUrl: 'https://example.com/image.png',
        });

        try {
          // Export CSV
          const exportResponse = await app.inject({
            method: 'GET',
            url: '/components/export/csv',
            headers: { authorization: `Bearer ${adminToken}` },
          });
          assert.equal(exportResponse.statusCode, 200);
          const csvContent = exportResponse.payload;

          // Verify the new 10-column format
          assert.ok(
            csvContent.includes(
              'ID,Name,Description,Category,Location,Image URL,Created At,Updated At,Total Quantity,Available Quantity',
            ),
          );
          assert.ok(csvContent.includes('Round Trip Component'));
          assert.ok(csvContent.includes('Microprocessors'));
          assert.ok(csvContent.includes('Robo Lab'));
          assert.ok(csvContent.includes('https://example.com/image.png'));

          // Re-import the exported CSV
          const importResponse = await app.inject({
            method: 'POST',
            url: '/components/import/csv',
            headers: {
              authorization: `Bearer ${adminToken}`,
              'content-type': 'text/csv',
            },
            body: csvContent,
          });

          assert.equal(importResponse.statusCode, 201);
          const body = importResponse.json();
          assert.equal(body.imported, 1);
          assert.equal(body.components[0].name, 'Round Trip Component');
          assert.equal(body.components[0].description, 'Test round trip');
          assert.equal(body.components[0].category, ComponentCategory.Microprocessors);
          assert.equal(body.components[0].location, Location.Robo_Lab);
          assert.equal(body.components[0].imageUrl, 'https://example.com/image.png');
          assert.equal(body.components[0].totalQuantity, 25);
          assert.equal(body.components[0].availableQuantity, 20);

          // Clean up both original and imported
          await deleteComponents([original.id, body.components[0].id]);
        } finally {
          // Ensure original is cleaned up even if test fails
          await deleteComponents([original.id]).catch(() => {});
        }
      });

      test('ignores unknown columns (ID, Created At, Updated At) from export', async () => {
        const csv = [
          'ID,Name,Description,Category,Location,Image URL,Created At,Updated At,Total Quantity,Available Quantity',
          'ignored-id,Imported Component,Description,Sensors,IoT Lab,https://example.com/img.png,2024-01-01T00:00:00.000Z,2024-01-01T00:00:00.000Z,15,10',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 201);
        const body = response.json();
        assert.equal(body.imported, 1);
        assert.equal(body.components[0].name, 'Imported Component');
        assert.equal(body.components[0].description, 'Description');
        assert.equal(body.components[0].category, ComponentCategory.Sensors);
        assert.equal(body.components[0].location, Location.IoT_Lab);
        assert.equal(body.components[0].imageUrl, 'https://example.com/img.png');
        assert.equal(body.components[0].totalQuantity, 15);
        assert.equal(body.components[0].availableQuantity, 10);
        // ID should be auto-generated, not from CSV
        assert.ok(body.components[0].id);
        assert.notEqual(body.components[0].id, 'ignored-id');

        await deleteComponents(body.components.map((c: { id: string }) => c.id));
      });

      test('skips fully empty rows', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          'Component 1,Desc,Sensors,IoT Lab,10,10',
          ',,,,,',
          'Component 2,Desc,Actuators,Robo Lab,20,20',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 201);
        const body = response.json();
        assert.equal(body.imported, 2);
        assert.equal(body.components[0].name, 'Component 1');
        assert.equal(body.components[1].name, 'Component 2');
        await deleteComponents(body.components.map((c: { id: string }) => c.id));
      });

      test('defaults totalQuantity to 0 and availableQuantity to total when absent', async () => {
        const csv =
          'Name,Description,Category,Location\nMinimal Component,Minimal desc,Sensors,IoT Lab';
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 201);
        const body = response.json();
        assert.equal(body.imported, 1);
        assert.equal(body.components[0].totalQuantity, 0);
        assert.equal(body.components[0].availableQuantity, 0);
        await deleteComponents(body.components.map((c: { id: string }) => c.id));
      });

      test('returns 400 when availableQuantity > totalQuantity', async () => {
        const csv =
          'Name,Description,Category,Location,Total Quantity,Available Quantity\nBad Component,Desc,Sensors,IoT Lab,10,15';
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.equal(body.error, 'import failed');
        assert.ok(Array.isArray(body.details));
        assert.ok(body.details[0].message.includes('cannot be greater than'));
      });

      test('returns 400 when a row has a blank name cell', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          'CsvGood Part,Desc,Sensors,IoT Lab,5,5',
          ',Has no name,Sensors,IoT Lab,5,5',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.equal(body.error, 'import failed');
        assert.deepEqual(body.details, [{ row: 2, message: 'name is required' }]);

        const getResponse = await app.inject({
          method: 'GET',
          url: '/components',
          headers: { authorization: `Bearer ${adminToken}` },
        });
        const components = getResponse.json().components;
        const imported = components.filter((c: { name: string }) => c.name === 'CsvGood Part');
        assert.equal(imported.length, 0);
      });

      test('returns 400 when a name is only spaces', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          '   ,Only spaces,Sensors,IoT Lab,5,5',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.deepEqual(body.details, [{ row: 1, message: 'name is required' }]);
      });

      test('returns 400 when totalQuantity is not a number', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          'CsvBad Total,Desc,Sensors,IoT Lab,abc,5',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.deepEqual(body.details, [
          { row: 1, message: 'totalQuantity must be a non-negative integer' },
        ]);
      });

      test('returns 400 when availableQuantity is not a number', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          'CsvBad Available,Desc,Sensors,IoT Lab,5,xyz',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.deepEqual(body.details, [
          { row: 1, message: 'availableQuantity must be a non-negative integer' },
        ]);
      });

      test('returns 400 when totalQuantity is not a whole number', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          'CsvBad Decimal,Desc,Sensors,IoT Lab,2.5,2',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.deepEqual(body.details, [
          { row: 1, message: 'totalQuantity must be a non-negative integer' },
        ]);
      });

      test('imports CSV sent as JSON body { csv }', async () => {
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'application/json',
          },
          payload: { csv: 'Name,Total Quantity\nCsvJson Part,3' },
        });

        assert.equal(response.statusCode, 201);
        const body = response.json();
        assert.equal(body.imported, 1);
        assert.equal(body.components[0].name, 'CsvJson Part');
        assert.equal(body.components[0].totalQuantity, 3);
        assert.equal(body.components[0].availableQuantity, 3);
        await deleteComponents(body.components.map((c: { id: string }) => c.id));
      });

      test('returns 400 when JSON body csv is empty', async () => {
        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'application/json',
          },
          payload: { csv: '' },
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.equal(body.error, 'CSV content is required');
      });

      test('reports every bad row and counts blank rows in row numbers', async () => {
        const csv = [
          'Name,Description,Category,Location,Total Quantity,Available Quantity',
          'CsvOk Part,Desc,Sensors,IoT Lab,5,5',
          ',,,,,',
          'CsvBad Category,Desc,NotACategory,IoT Lab,5,5',
          'CsvBad Negative,Desc,Sensors,IoT Lab,-1,0',
        ].join('\n');

        const response = await app.inject({
          method: 'POST',
          url: '/components/import/csv',
          headers: {
            authorization: `Bearer ${adminToken}`,
            'content-type': 'text/csv',
          },
          body: csv,
        });

        assert.equal(response.statusCode, 400);
        const body = response.json();
        assert.equal(body.error, 'import failed');
        assert.equal(body.details.length, 2);
        assert.equal(body.details[0].row, 3);
        assert.ok(body.details[0].message.includes('invalid category'));
        assert.deepEqual(body.details[1], {
          row: 4,
          message: 'totalQuantity must be a non-negative integer',
        });

        const getResponse = await app.inject({
          method: 'GET',
          url: '/components',
          headers: { authorization: `Bearer ${adminToken}` },
        });
        const components = getResponse.json().components;
        const imported = components.filter((c: { name: string }) => c.name === 'CsvOk Part');
        assert.equal(imported.length, 0);
      });
    });
  });

  describe('GET /components/:id - Get single component', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/components/test-id',
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns component by id (STUDENT)', async () => {
      const component = await createComponent({
        name: 'Student View Component',
        description: 'Visible to all roles',
        totalQuantity: 3,
        availableQuantity: 3,
      });

      const response = await app.inject({
        method: 'GET',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${studentToken}`,
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.component.id, component.id);

      await deleteAllData();
    });

    test('returns 404 for non-existent component', async () => {
      const response = await app.inject({
        method: 'GET',
        url: '/components/non-existent-id',
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
      });

      assert.equal(response.statusCode, 404);
      const body = response.json();
      assert.ok(body.error.includes('not found'));
    });

    test('returns component by id (ADMIN)', async () => {
      const component = await createComponent({
        name: 'Test Component',
        description: 'Test description',
        totalQuantity: 5,
        availableQuantity: 5,
        category: ComponentCategory.Actuators,
        location: Location.VLSI_Lab,
      });

      const response = await app.inject({
        method: 'GET',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.component.id, component.id);
      assert.equal(body.component.name, 'Test Component');
      assert.equal(body.component.description, 'Test description');
      assert.equal(body.component.totalQuantity, 5);
      assert.equal(body.component.availableQuantity, 5);
      assert.equal(body.component.category, ComponentCategory.Actuators);
      assert.equal(body.component.location, Location.VLSI_Lab);

      await deleteAllData();
    });
  });

  describe('POST /components - Create component', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        payload: {
          name: 'Test Component',
          totalQuantity: 10,
        },
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns 403 for STUDENT role', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: {
          authorization: `Bearer ${studentToken}`,
        },
        payload: {
          name: 'Test Component',
          quantity: 10,
        },
      });

      assert.equal(response.statusCode, 403);
    });

    test('returns 400 when name is missing', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
        payload: {
          quantity: 10,
        },
      });

      assert.equal(response.statusCode, 400);
      const body = response.json();
      assert.ok(body.error.includes('name is required'));
    });

    test('returns 400 when quantity is negative', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
        payload: {
          name: 'Test Component',
          totalQuantity: -1,
        },
      });

      assert.equal(response.statusCode, 400);
      const body = response.json();
      assert.ok(body.error.includes('totalQuantity'));
    });

    test('creates component with all fields (LA)', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: {
          authorization: `Bearer ${taToken}`,
        },
        payload: {
          name: 'New Component',
          description: 'Component description',
          totalQuantity: 25,
          category: ComponentCategory.Sensors,
          location: 'IoT Lab',
        },
      });

      assert.equal(response.statusCode, 201);
      const body = response.json();
      assert.ok(body.component.id);
      assert.equal(body.component.name, 'New Component');
      assert.equal(body.component.description, 'Component description');
      assert.equal(body.component.totalQuantity, 25);
      assert.equal(body.component.availableQuantity, 25);
      assert.equal(body.component.category, ComponentCategory.Sensors);
      assert.equal(body.component.location, Location.IoT_Lab);
      assert.ok(body.component.createdAt);
      assert.ok(body.component.updatedAt);

      await deleteComponents([body.component.id]);
    });

    test('returns 400 for invalid category', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          name: 'Invalid Cat Component',
          category: 'InvalidCategory',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('invalid category'));
    });

    test('returns 400 for invalid location', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          name: 'Invalid Loc Component',
          location: 'InvalidLocation',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('invalid location'));
    });

    test('returns 400 for invalid imageUrl - javascript protocol', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          name: 'Invalid URL Component',
          imageUrl: 'javascript:alert(1)',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('imageUrl must be a valid HTTP or HTTPS URL'));
    });

    test('returns 400 for invalid imageUrl - data protocol', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          name: 'Invalid URL Component',
          imageUrl: 'data:image/png;base64,abc123',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('imageUrl must be a valid HTTP or HTTPS URL'));
    });

    test('returns 400 for invalid imageUrl - not a URL', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          name: 'Invalid URL Component',
          imageUrl: 'not-a-valid-url',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('imageUrl must be a valid HTTP or HTTPS URL'));
    });

    test('creates component with valid imageUrl (HTTPS)', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          name: 'Component with Image',
          imageUrl: 'https://example.com/image.png',
          totalQuantity: 10,
        },
      });

      assert.equal(response.statusCode, 201);
      const body = response.json();
      assert.equal(body.component.imageUrl, 'https://example.com/image.png');

      await deleteComponents([body.component.id]);
    });

    test('creates component with minimal fields (ADMIN)', async () => {
      const response = await app.inject({
        method: 'POST',
        url: '/components',
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
        payload: {
          name: 'Minimal Component',
        },
      });

      assert.equal(response.statusCode, 201);
      const body = response.json();
      assert.ok(body.component.id);
      assert.equal(body.component.name, 'Minimal Component');
      assert.equal(body.component.totalQuantity, 0);
      assert.equal(body.component.availableQuantity, 0);
      assert.equal(body.component.description, null);
      assert.equal(body.component.category, null);
      assert.equal(body.component.location, null);

      await deleteComponents([body.component.id]);
    });
  });

  describe('PUT /components/:id - Update component', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'PUT',
        url: '/components/test-id',
        payload: {
          name: 'Updated Component',
        },
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns 403 for FACULTY role', async () => {
      const response = await app.inject({
        method: 'PUT',
        url: '/components/test-id',
        headers: {
          authorization: `Bearer ${facultyToken}`,
        },
        payload: {
          name: 'Updated Component',
        },
      });

      assert.equal(response.statusCode, 403);
    });

    test('returns 404 for non-existent component', async () => {
      const response = await app.inject({
        method: 'PUT',
        url: '/components/non-existent-id',
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
        payload: {
          name: 'Updated Component',
        },
      });

      assert.equal(response.statusCode, 404);
      const body = response.json();
      assert.ok(body.error.includes('not found'));
    });

    test('returns 400 when quantity is negative', async () => {
      const component = await createComponent({
        name: 'Test Component',
        totalQuantity: 10,
        availableQuantity: 10,
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
        payload: {
          totalQuantity: -5,
        },
      });

      assert.equal(response.statusCode, 400);
      const body = response.json();
      assert.ok(body.error.includes('totalQuantity'));

      await deleteAllData();
    });

    test('returns 400 when availableQuantity > totalQuantity', async () => {
      const component = await createComponent({
        name: 'Quant Test',
        totalQuantity: 10,
        availableQuantity: 10,
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/components/${component.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          availableQuantity: 15,
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('cannot be greater than'));
      await deleteAllData();
    });

    test('updates component partially (LA)', async () => {
      const component = await createComponent({
        name: 'Original Name',
        description: 'Original description',
        totalQuantity: 10,
        availableQuantity: 10,
        category: ComponentCategory.Sensors,
        location: Location.IoT_Lab,
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${taToken}`,
        },
        payload: {
          name: 'Updated Name',
          totalQuantity: 20,
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.component.id, component.id);
      assert.equal(body.component.name, 'Updated Name');
      assert.equal(body.component.description, 'Original description'); // unchanged
      assert.equal(body.component.totalQuantity, 20);
      assert.equal(body.component.availableQuantity, 20);
      assert.equal(body.component.category, ComponentCategory.Sensors); // unchanged
      assert.equal(body.component.location, Location.IoT_Lab); // unchanged

      await deleteAllData();
    });

    test('updates all fields (ADMIN)', async () => {
      const component = await createComponent({
        name: 'Original Name',
        description: 'Original description',
        totalQuantity: 10,
        availableQuantity: 10,
        category: ComponentCategory.Sensors,
        location: Location.IoT_Lab,
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
        payload: {
          name: 'Fully Updated Name',
          description: 'Fully updated description',
          totalQuantity: 30,
          category: ComponentCategory.Actuators,
          location: 'Robo Lab',
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.component.name, 'Fully Updated Name');
      assert.equal(body.component.description, 'Fully updated description');
      assert.equal(body.component.totalQuantity, 30);
      assert.equal(body.component.availableQuantity, 30);
      assert.equal(body.component.category, ComponentCategory.Actuators);
      assert.equal(body.component.location, Location.Robo_Lab);

      await deleteAllData();
    });

    test('can set optional fields to null (LA)', async () => {
      const component = await createComponent({
        name: 'Test Component',
        description: 'Has description',
        category: ComponentCategory.Microcontrollers,
        location: Location.VLSI_Lab,
        totalQuantity: 10,
        availableQuantity: 10,
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${taToken}`,
        },
        payload: {
          description: '',
          category: '',
          location: '',
        },
      });

      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.component.description, null);
      assert.equal(body.component.category, null);
      assert.equal(body.component.location, null);

      await deleteAllData();
    });

    test('returns 400 for invalid imageUrl on update - javascript protocol', async () => {
      const component = await createComponent({
        name: 'Test Component',
        totalQuantity: 10,
        availableQuantity: 10,
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/components/${component.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          imageUrl: 'javascript:alert(document.cookie)',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('imageUrl must be a valid HTTP or HTTPS URL'));

      await deleteAllData();
    });

    test('returns 400 for invalid imageUrl on update - file protocol', async () => {
      const component = await createComponent({
        name: 'Test Component',
        totalQuantity: 10,
        availableQuantity: 10,
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/components/${component.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          imageUrl: 'file:///etc/passwd',
        },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('imageUrl must be a valid HTTP or HTTPS URL'));

      await deleteAllData();
    });

    test('updates imageUrl with valid HTTPS URL', async () => {
      const component = await createComponent({
        name: 'Test Component',
        totalQuantity: 10,
        availableQuantity: 10,
      });

      const response = await app.inject({
        method: 'PUT',
        url: `/components/${component.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
        payload: {
          imageUrl: 'https://cdn.example.com/components/arduino.jpg',
        },
      });

      assert.equal(response.statusCode, 200);
      assert.equal(
        response.json().component.imageUrl,
        'https://cdn.example.com/components/arduino.jpg',
      );

      await deleteAllData();
    });
  });

  describe('DELETE /components/:id - Delete component', () => {
    test('returns 401 without token', async () => {
      const response = await app.inject({
        method: 'DELETE',
        url: '/components/test-id',
      });

      assert.equal(response.statusCode, 401);
    });

    test('returns 403 for STUDENT role', async () => {
      const response = await app.inject({
        method: 'DELETE',
        url: '/components/test-id',
        headers: {
          authorization: `Bearer ${studentToken}`,
        },
      });

      assert.equal(response.statusCode, 403);
    });

    test('returns 404 for non-existent component', async () => {
      const response = await app.inject({
        method: 'DELETE',
        url: '/components/non-existent-id',
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
      });

      assert.equal(response.statusCode, 404);
      const body = response.json();
      assert.ok(body.error.includes('not found'));
    });

    test('deletes component successfully (ADMIN)', async () => {
      const component = await createComponent({
        name: 'Component to Delete',
        totalQuantity: 5,
        availableQuantity: 5,
      });

      const response = await app.inject({
        method: 'DELETE',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
      });

      assert.equal(response.statusCode, 204);

      // Verify component is deleted
      const deletedComponent = await findComponentById(component.id);
      assert.equal(deletedComponent, null);
    });

    test('deletes component successfully (LA)', async () => {
      const component = await createComponent({
        name: 'Component to Delete',
        totalQuantity: 5,
        availableQuantity: 5,
      });

      const response = await app.inject({
        method: 'DELETE',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${taToken}`,
        },
      });

      assert.equal(response.statusCode, 204);

      const deletedComponent = await findComponentById(component.id);
      assert.equal(deletedComponent, null);
    });

    test('DELETE request with form-urlencoded content-type is accepted', async () => {
      const component = await createComponent({
        name: 'Component to Delete with Form',
        totalQuantity: 5,
        availableQuantity: 5,
      });

      const response = await app.inject({
        method: 'DELETE',
        url: `/components/${component.id}`,
        headers: {
          authorization: `Bearer ${adminToken}`,
          'content-type': 'application/x-www-form-urlencoded',
        },
        payload: '',
      });

      assert.equal(response.statusCode, 204);

      // Verify component is deleted
      const deletedComponent = await findComponentById(component.id);
      assert.equal(deletedComponent, null);
    });

    test('returns 400 when component is in use', async () => {
      const component = await createComponent({
        name: 'In Use Component',
        totalQuantity: 5,
        availableQuantity: 5,
      });

      await createRequest({
        userId: adminUserId,
        targetFacultyId: facultyUserId,
        projectTitle: 'Usage Test',
        items: [{ componentId: component.id, quantity: 1 }],
      });

      const response = await app.inject({
        method: 'DELETE',
        url: `/components/${component.id}`,
        headers: { authorization: `Bearer ${adminToken}` },
      });

      assert.equal(response.statusCode, 400);
      assert.ok(response.json().error.includes('used in one or more requests'));

      await deleteAllRequests();
      await deleteAllData();
    });
  });

  describe('Integration tests', () => {
    test('Full CRUD workflow - create, read, update, delete (ADMIN)', async () => {
      // Create
      const createResponse = await app.inject({
        method: 'POST',
        url: '/components',
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
        payload: {
          name: 'Workflow Component',
          description: 'Testing full workflow',
          quantity: 15,
          category: ComponentCategory.Sensors,
          location: 'VLSI Lab',
        },
      });

      assert.equal(createResponse.statusCode, 201);
      const created = createResponse.json().component;
      assert.ok(created.id);

      // Read
      const readResponse = await app.inject({
        method: 'GET',
        url: `/components/${created.id}`,
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
      });

      assert.equal(readResponse.statusCode, 200);
      assert.equal(readResponse.json().component.name, 'Workflow Component');

      // Update
      const updateResponse = await app.inject({
        method: 'PUT',
        url: `/components/${created.id}`,
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
        payload: {
          totalQuantity: 25,
        },
      });

      assert.equal(updateResponse.statusCode, 200);
      assert.equal(updateResponse.json().component.totalQuantity, 25);
      assert.equal(updateResponse.json().component.availableQuantity, 25);

      // Delete
      const deleteResponse = await app.inject({
        method: 'DELETE',
        url: `/components/${created.id}`,
        headers: {
          authorization: `Bearer ${adminToken}`,
        },
      });

      assert.equal(deleteResponse.statusCode, 204);
    });
  });
});
