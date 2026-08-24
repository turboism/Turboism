const model = turboism.cubism.model.snapshot();
const parameters = turboism.cubism.parameters.snapshot().parameters;
const first = parameters.length === 0
  ? { parameters: [] }
  : turboism.cubism.parameters.getMany([parameters[0].id]);
console.log(`modelIdPresent=${typeof model.id === 'string' && model.id.length > 0}`);
console.log(`parameterCountPresent=${Array.isArray(model.parameters)}`);
console.log(`getManyCount=${first.parameters.length}`);
