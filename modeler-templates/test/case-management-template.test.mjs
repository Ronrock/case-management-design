import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import BpmnModdle from 'bpmn-moddle';

/**
 * The published modeling vocabulary (Workstream 1, Task 5).
 *
 * A model author never sees the platform reject a misspelled extension property — the modeler
 * writes whatever the template tells it to, and a wrong namespace or a stale vendor prefix only
 * surfaces when a live case reaches the element. These tests therefore assert the template and
 * the shipped sample agree with the namespaces `BpmnReleaseValidator` enforces, and that a
 * moddle round-trip preserves them exactly.
 */

const OPERATON_NS = 'http://operaton.org/schema/1.0/bpmn';
const CASEMGMT_NS = 'https://casemgmt.org/bpmn';

const NAMESPACE_BY_PREFIX = {
  operaton: OPERATON_NS,
  casemgmt: CASEMGMT_NS
};

const TEMPLATE_PROPERTY_VALUES = {
  id: 'programmatic-case',
  'operaton:formKey': 'forms/programmatic-case',
  'operaton:candidateGroups': 'case-workers',
  'casemgmt:slaTargetId': 'initial-response',
  'casemgmt:stage': 'true',
  'casemgmt:milestoneId': 'programmatic-milestone'
};

const EXTENSION_PACKAGES = {
  operaton: {
    name: 'Operaton',
    uri: OPERATON_NS,
    prefix: 'operaton',
    xml: { tagAlias: 'lowerCase' },
    types: [{
      name: 'UserTask',
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'formKey', type: 'String', isAttr: true },
        { name: 'candidateGroups', type: 'String', isAttr: true }
      ]
    }]
  },
  casemgmt: {
    name: 'Case management',
    uri: CASEMGMT_NS,
    prefix: 'casemgmt',
    xml: { tagAlias: 'lowerCase' },
    types: [
      {
        name: 'Process',
        extends: ['bpmn:Process'],
        properties: [{ name: 'slaTargetId', type: 'String', isAttr: true }]
      },
      {
        name: 'UserTask',
        extends: ['bpmn:UserTask'],
        properties: [{ name: 'slaTargetId', type: 'String', isAttr: true }]
      },
      {
        name: 'Stage',
        extends: ['bpmn:SubProcess'],
        properties: [{ name: 'stage', type: 'String', isAttr: true }]
      },
      {
        name: 'Milestone',
        extends: ['bpmn:IntermediateThrowEvent', 'bpmn:EndEvent'],
        properties: [{ name: 'milestoneId', type: 'String', isAttr: true }]
      }
    ]
  }
};

const TEMPLATES = JSON.parse(
  await readFile(new URL('../case-management.json', import.meta.url), 'utf8')
);
const SAMPLE = await readFile(
  new URL('../../case-management-poc-app/src/main/resources/processes/complaint-bpmn.bpmn',
    import.meta.url),
  'utf8'
);

const bindingsOf = (id) =>
  TEMPLATES.find((template) => template.id === id).properties
    .map((property) => property.binding.name);

test('templates bind engine properties to the Operaton namespace', () => {
  const bindings = bindingsOf('org.casemgmt.user-task');

  assert.ok(bindings.includes('operaton:formKey'));
  assert.ok(bindings.includes('operaton:candidateGroups'));
});

test('templates bind platform properties to the case-management namespace', () => {
  assert.ok(bindingsOf('org.casemgmt.user-task').includes('casemgmt:slaTargetId'));
  assert.ok(bindingsOf('org.casemgmt.milestone').includes('casemgmt:milestoneId'));
  assert.ok(bindingsOf('org.casemgmt.stage').includes('casemgmt:stage'));
});

/**
 * `camunda:` was the prefix these templates shipped with. It is not merely cosmetic: the
 * validator reads engine properties from the Operaton URI, so a `camunda:`-bound template
 * produces models whose form and candidate groups are invisible to publication.
 */
test('no template still binds a retired vendor prefix', () => {
  const every = TEMPLATES.flatMap((template) =>
    template.properties.map((property) => property.binding.name));

  assert.deepEqual(every.filter((name) => name.startsWith('camunda:')), []);
});

test('every template property binds into a declared namespace', () => {
  const every = TEMPLATES.flatMap((template) =>
    template.properties.map((property) => property.binding.name));

  for (const name of every) {
    if (!name.includes(':')) continue;
    const prefix = name.split(':')[0];
    assert.ok(['operaton', 'casemgmt'].includes(prefix),
      `unexpected namespace prefix '${prefix}' in binding '${name}'`);
  }
});

test('every template property application round-trips with qualified values resolved by URI', async () => {
  const moddle = new BpmnModdle(EXTENSION_PACKAGES);
  const definitions = moddle.create('bpmn:Definitions', {
    id: 'programmatic-definitions',
    targetNamespace: 'https://casemgmt.org/test/programmatic-template-application'
  });
  const process = moddle.create('bpmn:Process', { id: 'untemplated-process' });
  definitions.rootElements = [process];

  const applications = [];

  for (const template of TEMPLATES) {
    for (const appliesTo of template.appliesTo) {
      const target = appliesTo === 'bpmn:Process'
        ? process
        : moddle.create(appliesTo, {
          id: `${template.id}-${appliesTo.slice('bpmn:'.length)}`
        });

      if (target !== process) process.get('flowElements').push(target);

      for (const property of template.properties) {
        assert.equal(property.binding.type, 'property');
        assert.ok(Object.hasOwn(TEMPLATE_PROPERTY_VALUES, property.binding.name),
          `no test value for binding '${property.binding.name}'`);
        if (property.value !== undefined) {
          assert.equal(property.value, TEMPLATE_PROPERTY_VALUES[property.binding.name]);
        }
        target.set(property.binding.name, TEMPLATE_PROPERTY_VALUES[property.binding.name]);
      }

      applications.push({
        template,
        targetId: target.id,
        targetType: appliesTo
      });
    }
  }

  const { xml } = await moddle.toXML(definitions);
  const reloaded = await moddle.fromXML(xml);
  const reloadedProcess = reloaded.rootElement.rootElements
    .find((element) => element.$type === 'bpmn:Process');

  for (const { template, targetId, targetType } of applications) {
    const target = targetType === 'bpmn:Process'
      ? reloadedProcess
      : findFlowElement(reloadedProcess, targetId);

    assert.ok(target, `${template.id} application target '${targetId}' was not reloaded`);

    for (const property of template.properties) {
      const bindingName = property.binding.name;
      const expected = TEMPLATE_PROPERTY_VALUES[bindingName];

      if (!bindingName.includes(':')) {
        assert.equal(target.get(bindingName), expected);
        continue;
      }

      const [prefix, localName] = bindingName.split(':');
      assert.equal(attributeByNamespaceUri(target, NAMESPACE_BY_PREFIX[prefix], localName),
        expected,
        `${template.id} binding '${bindingName}' did not resolve by namespace URI`);
    }
  }
});

/**
 * The round trip: open the shipped sample, write it back out, and open it again. Values and the
 * namespace URIs carrying them must be byte-for-byte the same properties on the way out as on
 * the way in — that is what "the modeler did not quietly change my model" means.
 */
test('a sample opened, saved and reopened keeps its exact namespaces and values', async () => {
  const moddle = new BpmnModdle(EXTENSION_PACKAGES);

  const first = await moddle.fromXML(SAMPLE);
  const { xml } = await moddle.toXML(first.rootElement);
  const second = await moddle.fromXML(xml);

  for (const parsed of [first, second]) {
    const process = parsed.rootElement.rootElements.find((e) => e.$type === 'bpmn:Process');
    assert.equal(attributeByNamespaceUri(process, CASEMGMT_NS, 'slaTargetId'), 'resolution');

    const register = findFlowElement(process, 'register');
    assert.equal(attributeByNamespaceUri(register, OPERATON_NS, 'formKey'), 'registerForm');
    assert.equal(attributeByNamespaceUri(register, OPERATON_NS, 'candidateGroups'), 'intake');

    const close = findFlowElement(process, 'close-complaint');
    assert.equal(attributeByNamespaceUri(close, OPERATON_NS, 'formKey'), 'closeForm');
    assert.equal(attributeByNamespaceUri(close, CASEMGMT_NS, 'slaTargetId'), undefined);

    const acknowledged = findFlowElement(process, 'acknowledged');
    assert.equal(attributeByNamespaceUri(
      acknowledged, CASEMGMT_NS, 'milestoneId'), 'acknowledged');

    const intake = findFlowElement(process, 'intake');
    assert.equal(attributeByNamespaceUri(intake, CASEMGMT_NS, 'stage'), 'true');
  }

  // The declarations themselves must survive, not just the resolved values.
  assert.ok(xml.includes(OPERATON_NS));
  assert.ok(xml.includes(CASEMGMT_NS));
});

/** Depth-first lookup: stages nest their children, so a flat scan would miss them. */
function findFlowElement(container, id) {
  for (const element of container.flowElements ?? []) {
    if (element.id === id) return element;
    const nested = findFlowElement(element, id);
    if (nested) return nested;
  }
  return undefined;
}

/** Resolve only modeled attributes whose declaring package owns the exact namespace URI. */
function attributeByNamespaceUri(element, namespace, localName) {
  const property = element?.$descriptor.properties.find((candidate) =>
    candidate.isAttr &&
    candidate.ns.localName === localName &&
    candidate.definedBy.$pkg.uri === namespace);

  assert.ok(property, `attribute '{${namespace}}${localName}' is not namespace-resolved`);
  return element.get(property.ns.name);
}
