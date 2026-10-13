import java.lang.management.ManagementFactory;
import java.util.ArrayList;
public class cpu_probe {
  public static void main(String[] args) throws Exception {
    var bean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    long start = System.nanoTime(), cpuStart = bean.getProcessCpuTime();
    var workers = new ArrayList<Thread>();
    for (int i = 0; i < 4; i++) {
      var worker = new Thread(() -> { double n = 1; while (!Thread.interrupted()) { n = Math.sqrt(n + 1); } });
      workers.add(worker); worker.start();
    }
    Thread.sleep(5000);
    for (var worker : workers) worker.interrupt();
    for (var worker : workers) worker.join();
    double wall = (System.nanoTime() - start) / 1e9;
    double cpu = (bean.getProcessCpuTime() - cpuStart) / 1e9;
    System.out.printf("{\"wallSeconds\":%.3f,\"cpuSeconds\":%.3f,\"cores\":%.3f}%n", wall, cpu, cpu / wall);
  }
}
