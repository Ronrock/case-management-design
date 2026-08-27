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

/**
 * The round trip: open the shipped sample, write it back out, and open it again. Values and the
 * namespace URIs carrying them must be byte-for-byte the same properties on the way out as on
 * the way in — that is what "the modeler did not quietly change my model" means.
 */
test('a sample opened, saved and reopened keeps its exact namespaces and values', async () => {
  const moddle = new BpmnModdle();

  const first = await moddle.fromXML(SAMPLE);
  const { xml } = await moddle.toXML(first.rootElement);
  const second = await moddle.fromXML(xml);

  for (const parsed of [first, second]) {
    const process = parsed.rootElement.rootElements.find((e) => e.$type === 'bpmn:Process');

    const register = findFlowElement(process, 'register');
    assert.equal(attr(register, OPERATON_NS, 'formKey'), 'registerForm');
    assert.equal(attr(register, OPERATON_NS, 'candidateGroups'), 'intake');

    const close = findFlowElement(process, 'close-complaint');
    assert.equal(attr(close, OPERATON_NS, 'formKey'), 'closeForm');
    assert.equal(attr(close, CASEMGMT_NS, 'slaTargetId'), 'resolution');

    const acknowledged = findFlowElement(process, 'acknowledged');
    assert.equal(attr(acknowledged, CASEMGMT_NS, 'milestoneId'), 'acknowledged');

    const intake = findFlowElement(process, 'intake');
    assert.equal(attr(intake, CASEMGMT_NS, 'stage'), 'true');
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

/**
 * bpmn-moddle keeps attributes it has no descriptor for in `$attrs`, keyed either by the
 * resolved `{uri}local` form or by the source prefix. Both are checked so the assertion is about
 * the namespace the attribute resolves to rather than the prefix the author happened to type.
 */
function attr(element, namespace, localName) {
  const attrs = element?.$attrs ?? {};
  const resolved = attrs[`{${namespace}}${localName}`];
  if (resolved !== undefined) return resolved;
  const prefix = namespace === OPERATON_NS ? 'operaton' : 'casemgmt';
  return attrs[`${prefix}:${localName}`];
}
