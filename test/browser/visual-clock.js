/* Fixture-only calendar input. Time advances normally for native lifecycles. */
(() => {
  const NativeDate = window.Date;
  const epoch = NativeDate.UTC(2026, 9, 15, 12);
  const started = performance.now();
  const now = () => epoch + Math.floor(performance.now() - started);
  window.visualClockEpoch = epoch;
  window.Date = new Proxy(NativeDate, {
    construct(target, args, newTarget) {
      return Reflect.construct(target, args.length ? args : [now()], newTarget);
    },
    apply(target) {
      return new target(now()).toString();
    },
    get(target, property, receiver) {
      return property === 'now' ? now : Reflect.get(target, property, receiver);
    }
  });
})();
