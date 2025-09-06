import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Nodo {
    private int numeroNodo;
    private int puertoEscucha;
    private int puertoSiguiente;
    private String hostSiguiente;
    private boolean tieneToken;
    private boolean activo;

    // Servidores y clientes
    private ServerSocket servidor;
    private Socket conexionSiguiente;
    private PrintWriter salidaSiguiente;
    private BufferedReader entradaSiguiente;

    // Cola de mensajes por enviar
    private Queue<String> colaMensajes;
    private ExecutorService poolHilos;

    // Configuración del anillo
    private static final int PUERTO_BASE = 8000;
    private static final String HOST_LOCAL = "localhost";

    public Nodo(int numeroNodo) {
        this.numeroNodo = numeroNodo;
        this.puertoEscucha = PUERTO_BASE + numeroNodo;
        this.tieneToken = (numeroNodo == 0); // El nodo 0 inicia con el token
        this.activo = true;
        this.colaMensajes = new ConcurrentLinkedQueue<>();
        this.poolHilos = Executors.newFixedThreadPool(3);

        System.out.println("🟢 Nodo " + numeroNodo + " inicializado");
        System.out.println("   Puerto de escucha: " + puertoEscucha);
        System.out.println("   Tiene token inicial: " + tieneToken);
    }

    public void iniciar(int totalNodos) {
        try {
            // Calcular puerto del siguiente nodo (topología circular)
            int siguienteNodo = (numeroNodo + 1) % totalNodos;
            this.puertoSiguiente = PUERTO_BASE + siguienteNodo;
            this.hostSiguiente = HOST_LOCAL;

            System.out.println("   Se conectará al nodo " + siguienteNodo + " en puerto " + puertoSiguiente);

            // Iniciar servidor para recibir conexiones
            iniciarServidor();

            // Esperar un momento para que todos los nodos inicien sus servidores
            Thread.sleep(2000);

            // Conectar al siguiente nodo
            conectarSiguienteNodo();

            // Iniciar hilos de trabajo
            iniciarHilos();

            // Iniciar interfaz de usuario
            iniciarInterfazUsuario();

        } catch (Exception e) {
            System.err.println("[MARTIN] CRITICAL: Node initialization failed - " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void iniciarServidor() throws IOException {
        servidor = new ServerSocket(puertoEscucha);
        System.out.println("🔗 Servidor iniciado en puerto " + puertoEscucha);

        poolHilos.submit(() -> {
            try {
                while (activo) {
                    Socket clienteSocket = servidor.accept();
                    System.out.println("📥 Nueva conexión recibida desde " + clienteSocket.getInetAddress());

                    // Manejar la conexión entrante
                    poolHilos.submit(() -> manejarConexionEntrante(clienteSocket));
                }
            } catch (IOException e) {
                if (activo) {
                    System.err.println("❌ Error en servidor: " + e.getMessage());
                }
            }
        });
    }

    private void conectarSiguienteNodo() {
        poolHilos.submit(() -> {
            int intentos = 0;
            while (intentos < 10 && activo) {
                try {
                    Thread.sleep(1000); // Esperar antes de intentar conectar

                    conexionSiguiente = new Socket(hostSiguiente, puertoSiguiente);
                    salidaSiguiente = new PrintWriter(conexionSiguiente.getOutputStream(), true);
                    entradaSiguiente = new BufferedReader(new InputStreamReader(conexionSiguiente.getInputStream()));

                    System.out.println("🔗 Conectado al nodo siguiente en " + hostSiguiente + ":" + puertoSiguiente);
                    break;

                } catch (IOException | InterruptedException e) {
                    intentos++;
                    System.out.println("⏳ Intento " + intentos + " de conexión al siguiente nodo...");

                    if (intentos >= 10) {
                        System.err.println("❌ No se pudo conectar al siguiente nodo después de 10 intentos");
                        return;
                    }
                }
            }
        });
    }

    private void manejarConexionEntrante(Socket socket) {
        try {
            BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String mensaje;

            while ((mensaje = entrada.readLine()) != null && activo) {
                procesarMensaje(mensaje);
            }
        } catch (IOException e) {
            System.err.println("❌ Error manejando conexión entrante: " + e.getMessage());
        }
    }

    private void procesarMensaje(String mensaje) {
        System.out.println("📨 Mensaje recibido: " + mensaje);

        if (mensaje.startsWith("TOKEN")) {
            // Recibir el token
            tieneToken = true;
            System.out.println("🎯 TOKEN RECIBIDO - Nodo " + numeroNodo + " ahora tiene el token");

            // Procesar cola de mensajes
            procesarColaMensajes();

            // Después de un tiempo, pasar el token al siguiente nodo
            poolHilos.submit(() -> {
                try {
                    Thread.sleep(3000); // Mantener token por 3 segundos
                    pasarToken();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

        } else if (mensaje.startsWith("DATA:")) {
            // Procesar mensaje de datos
            String[] partes = mensaje.split(":", 3);
            if (partes.length >= 3) {
                int destino = Integer.parseInt(partes[1]);
                String datos = partes[2];

                if (destino == numeroNodo) {
                    System.out.println("📬 MENSAJE PARA ESTE NODO: " + datos);
                } else {
                    System.out.println("📤 Reenviando mensaje para nodo " + destino + ": " + datos);
                    if (salidaSiguiente != null) {
                        salidaSiguiente.println(mensaje);
                    }
                }
            }
        }
    }

    private void procesarColaMensajes() {
        while (!colaMensajes.isEmpty() && tieneToken) {
            String mensaje = colaMensajes.poll();
            System.out.println("📤 Enviando mensaje desde cola: " + mensaje);
            if (salidaSiguiente != null) {
                salidaSiguiente.println(mensaje);
            }
            try {
                Thread.sleep(500); // Pausa entre envíos
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void pasarToken() {
        if (tieneToken) {
            tieneToken = false;
            System.out.println("🎯 Pasando TOKEN al siguiente nodo...");
            if (salidaSiguiente != null) {
                salidaSiguiente.println("TOKEN");
            }
        }
    }

    private void iniciarHilos() {
        // Hilo para mostrar estado periódicamente
        poolHilos.submit(() -> {
            while (activo) {
                try {
                    Thread.sleep(10000); // Cada 10 segundos
                    mostrarEstado();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void mostrarEstado() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("📊 ESTADO DEL NODO " + numeroNodo);
        System.out.println("   Token: " + (tieneToken ? "✅ SÍ" : "❌ NO"));
        System.out.println("   Mensajes en cola: " + colaMensajes.size());
        System.out.println("   Conexión siguiente: " + (salidaSiguiente != null ? "✅ Activa" : "❌ Inactiva"));
        System.out.println("=".repeat(50) + "\n");
    }

    private void iniciarInterfazUsuario() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\n🎮 INTERFAZ DE USUARIO - NODO " + numeroNodo);
        System.out.println("Comandos disponibles:");
        System.out.println("  send <nodo_destino> <mensaje> - Enviar mensaje a otro nodo");
        System.out.println("  status - Mostrar estado actual");
        System.out.println("  exit - Salir del programa");
        System.out.println();

        while (activo) {
            try {
                System.out.print("Nodo[" + numeroNodo + "]> ");
                String comando = scanner.nextLine().trim();

                if (comando.startsWith("send ")) {
                    procesarComandoSend(comando);
                } else if (comando.equals("status")) {
                    mostrarEstado();
                } else if (comando.equals("exit")) {
                    System.out.println("👋 Cerrando nodo...");
                    cerrar();
                    break;
                } else if (!comando.isEmpty()) {
                    System.out.println("❓ Comando no reconocido: " + comando);
                }
            } catch (Exception e) {
                System.err.println("❌ Error en interfaz de usuario: " + e.getMessage());
            }
        }
        scanner.close();
    }

    private void procesarComandoSend(String comando) {
        try {
            String[] partes = comando.split(" ", 3);
            if (partes.length < 3) {
                System.out.println("❌ Formato incorrecto. Usar: send <nodo_destino> <mensaje>");
                return;
            }

            int destino = Integer.parseInt(partes[1]);
            String mensaje = partes[2];
            String mensajeCompleto = "DATA:" + destino + ":" + mensaje;

            if (tieneToken) {
                System.out.println("📤 Enviando inmediatamente (tengo el token): " + mensaje);
                if (salidaSiguiente != null) {
                    salidaSiguiente.println(mensajeCompleto);
                }
            } else {
                System.out.println("⏳ Mensaje agregado a la cola (esperando token): " + mensaje);
                colaMensajes.offer(mensajeCompleto);
            }

        } catch (NumberFormatException e) {
            System.out.println("❌ El número de nodo debe ser un entero válido");
        }
    }

    private void cerrar() {
        activo = false;

        try {
            if (conexionSiguiente != null) conexionSiguiente.close();
            if (servidor != null) servidor.close();
            poolHilos.shutdown();

            if (!poolHilos.awaitTermination(5, TimeUnit.SECONDS)) {
                poolHilos.shutdownNow();
            }
        } catch (Exception e) {
            System.err.println("❌ Error al cerrar: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("❌ Uso: java Nodo <numero_nodo> <total_nodos>");
            System.out.println("   Ejemplo: java Nodo 0 4");
            System.exit(1);
        }

        try {
            int numeroNodo = Integer.parseInt(args[0]);
            int totalNodos = Integer.parseInt(args[1]);

            if (numeroNodo < 0 || numeroNodo >= totalNodos) {
                System.out.println("❌ El número de nodo debe estar entre 0 y " + (totalNodos - 1));
                System.exit(1);
            }

            System.out.println("🚀 Iniciando Token Ring...");
            System.out.println("   Total de nodos: " + totalNodos);
            System.out.println("   Este nodo: " + numeroNodo);
            System.out.println();

            Nodo nodo = new Nodo(numeroNodo);
            nodo.iniciar(totalNodos);

        } catch (NumberFormatException e) {
            System.out.println("❌ Los argumentos deben ser números enteros válidos");
            System.exit(1);
        }
    }
}