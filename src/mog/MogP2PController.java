package mog;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("UnusedAssignment")
public class MogP2PController {
    
    //Diretório mogShared
    public static final String mogShare = "mogShare\\";
    
    //Defines de tipos de mensagem do protocolo
    private final String MSG_ENTR = "ENTR"; //mensagem solicitando participação na rede P2P
    private final String MSG_PESQ = "PESQ"; //mensagem de pesquisa por um arquivo
    private final String MSG_DOWN = "DOWN"; //mensagem solicitando download de arquivo para um peer
    private final String MSG_EXST = "EXST"; //mensagem de arquivo encontrado
    private final String MSG_PING = "PING"; //mensagem de ping
    
    //Campo nulo padrão de nome de arquivo
    private final String ARQ_NULO = "                    ";//20 char
    
    //Campo nulo padrão de endereço IP
    private final String EIP_NULO = "               ";//15 char
    
    //Defines dos tamanhos de campos das mensagens
    private final int tam_tipomsg = 4; //4 char para tipo de msg
    private final int tam_nomearq = ARQ_NULO.length(); //tamanho para nome de arquivo
    private final int tam_ipremet = EIP_NULO.length(); //tamanho para ip do remetente da msg
    private final int tam_tmtoliv = 1; //1 char para contador de TTL da msg
    
    //Define de tamanho da mensagem
    private final int tam_msg = 0
            + tam_tipomsg
            + tam_nomearq
            + tam_ipremet
            + tam_tmtoliv;
    
    //porta do protocolo
    private final int mog_port = 12345;
    
    //Defines de intervalos do protocolo (em milissegundos)
    private final long tempoComResposta = 4000;
    private final long tempoSemResposta = 1000;
    private final long mogTime = 20000; //intervalo de tempo entre cada ping.
    
    private final int ttl_inicial = 3;
    
    //Tabela primária - peers usados para pesquisa (cada nó da lista é um IP)
    private final ArrayList<String> peerspesq = new ArrayList<String>();
    
    //Tabela secundária - peers que atingiram o peer local com alguma mensagem (direta ou indiretamente)
    private final ArrayList<String> peers_reached = new ArrayList<String>();
    
    //Tamanho máximo da tabela primária - e, consequentemente, do tabela de resposta a MSG_ENTR
    private final int tam_tabela_prim = 2;
    
    private int cont1 = 0;//linha da tabela primária usada como resposta a MSG_ENTR
    private int cont2 = 0;//linha da tabela secundária usada como resposta a MSG_ENTR
    private String ultimo_IP_sobrep = "";//ultimo IP que deve ser usado para sobrepor tabelas de resposta
    private int ctrl_sobrep = 1;
    
    //IP de um peer pré-conhecido
    private String peer_inicial = "ARTHENCOU-PC";
    
    //Tabela de pesquisas ativas
    private final HashMap<String,ArrayList<PingsListNode>> pesquisas_ativas = 
            new HashMap<String,ArrayList<PingsListNode>>();
    
    
    public MogP2PController(){
        
    }
    
    public MogP2PController(String peer_inicial){
        this.peer_inicial = peer_inicial;
    }
    
    public void iniciar() {
        System.out.println("Entrou em iniciar()");
        
        boolean exit = false;
        
        //estabelecer uma conexão (socket) com o peer inicial pré conhecido
        Socket socket_peer_inicial = null;
        try {
            socket_peer_inicial = new Socket(peer_inicial, mog_port);
        } catch(Exception e) {
            //assinalando que o peer inicial não está disponível
            exit = true;
        }
        
        /* As threads a seguir não devem ser iniciadas antes da tentativa de
         * conexão com o peer pré-conhecido. Caso contrário, há a possibilidade
         * de se tentar conectar com a própria instância da aplicação. */
        
        //criando mecanismo de escuta por qualquer mensagem remota
        new Thread(new ThreadAceitaConexoes()).start();
        
        //criando thread que pinga os IPs da lista primária e remove os inexistentes
        new Thread( new ThreadPingaTodos() ).start();
        
        /* TODO: filtrar melhor quais IPs nao posso incluir na tabela.
         * Por exemplo: não posso incluir meu próprio IP. */
        
        //encerrando procedimento de inicialização caso não haja ninguém na rede
        if (exit) {
            return;
        }
        
        //enviar ao peer inicial uma mensagem do tipo ENTRADA
        OutputStream buffer_out = null;
        try{
            buffer_out = socket_peer_inicial.getOutputStream();
        }
        catch(Exception e) {}
        enviarMsg(MSG_ENTR, null, ARQ_NULO, buffer_out);
        
        //Criando buffer de entrada
        InputStream buffer_in = null;
        try {
            buffer_in = socket_peer_inicial.getInputStream();
        } catch (Exception e) {
            System.out.println("Problema ao criar buffer de entrada");
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, e);
        }
        
        //Recebendo tabela
        ObjectInputStream ois = null;
        ArrayList<String> tblainic = null;
        try {
            //Obtendo a tabela serializada
            ois = new ObjectInputStream(buffer_in);
            //Desserializando tabela
            tblainic = (ArrayList<String>) ois.readObject();
        } catch (IOException ex) {
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ClassNotFoundException cnfe){
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, cnfe);
        }
        //Verificando se a tabela recebida é menor do que o tamanho máximo
        if ( tblainic.size() < this.tam_tabela_prim ) {
            //Caso seja menor, adicione o IP de peer_inicial
            String ip = socket_peer_inicial.getInetAddress().toString().split("/")[1];
            tblainic.add(ip);
        }
        //Guardando tabela
        peerspesq.addAll(tblainic);
        
        for(String ip:peerspesq){
            System.out.println("peer: "+ip);
        }
    }
    
    @SuppressWarnings("SleepWhileInLoop")
    public void pesquisar(final String termobusca){
        
        System.out.println("Entrou em pesquisar(\""+termobusca+"\")");
        
        //Enviando uma mensagem de pesquisa para cada peer na tabela de pesquisa
        for (String peer:peerspesq){
            enviarMsg(MSG_PESQ, peer, termobusca, null);
        }
        
        System.out.println("Enviou mensagem para todos da lista");
        
        //Adicionando essa pesquisa ao mapa de pesquisas
        synchronized(pesquisas_ativas){
            ArrayList<PingsListNode> pings_pesquisa;
            pings_pesquisa = new ArrayList<PingsListNode>();
            pesquisas_ativas.put(termobusca, pings_pesquisa);
        }
        
        /*Recebendo alguma resposta durante tempoComResposta - a thread de
         *espera por conexões já se encarrega dessa tarefa*/

        //Aguardando tempoComResposta milissegundos
        try {Thread.sleep(tempoComResposta);} catch(Exception e) {}
        
        System.out.println("Expirou tempoComResposta");
        
        ArrayList<PingsListNode> pings_list;
        synchronized(pesquisas_ativas){
            //Obtendo a lista de pings para os peers que responderam à pesquisa.
            pings_list = pesquisas_ativas.get(termobusca);
            //Retirando termobusca do mapa de pesquisas
            pesquisas_ativas.remove(termobusca);
        }
        if(pings_list == null){
            System.out.println("Esse arquivo ainda nao existe na rede Mog P2P");
            return;
        }
        //Ordenando a lista de pings
        Collections.sort(pings_list);
        //Solicitando download de arquivo para o peer com menor ping disponível
        boolean baixado = false;
        for (PingsListNode node:pings_list){
            System.out.println("Consultando primeiro da lista de pings");
            if(!baixado){
                try {
                    
                    try {Thread.sleep(1500);} catch(Exception e) {}
                    
                    //Criando socket com o peer eleito
                    Socket socket = new Socket(node.host_ip, mog_port);
                    //Obtendo o buffer de saída
                    /**OutputStream out = node.out;/**/
                    OutputStream out = socket.getOutputStream();
                    System.out.println("Enviando MSG_DOWN para "+node.host_ip);
                    //Enviando mensagem BAIXAR
                    enviarMsg(MSG_DOWN, null, termobusca, out);
                    //Obtendo buffer de entrada
                    /**InputStream in = node.in;/**/
                    InputStream in = socket.getInputStream();
                    //Criando buffer de leitura de texto
                    BufferedReader br = new BufferedReader(new InputStreamReader(in));
                    //Recebendo o tamanho do array de bytes
                    String l = br.readLine();
                    System.out.println("l = "+l);
                    int len = Integer.parseInt(l);
                    //Criando buffer de leitura de bytes
                    DataInputStream dis = new DataInputStream(in);
                    //Recebendo o arquivo em um array de bytes de tamanho len
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    //Salvando arquivo
                    salvarArquivo(termobusca, data);

                    baixado = true;
                    //Nota: não me preocupo em fechar o socket. O peer remoto fechará.
                } catch (UnknownHostException ex) {
                    Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                } catch (IOException ex) {
                    Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
            /**
            //Fechando socket
            try {node.socket.close();} catch(Exception e) {}
            /**/
        }
    }
    
    private void processarMsgRec(
            String mensagem, 
            Socket socket, 
            InputStream in, 
            OutputStream out)
    {
        System.out.println("Entrou em processar MsgRec(msg: "+mensagem+")");
        
        //Obtendo ip do peer remoto
        String ip = socket.getInetAddress().toString().split("/")[1];
        
        /*
         * Uma mensagem tem os seguintes campos na respectiva ordem:
         * | tipomsg | nomearq | ipremet | tmtoliv |
         */
        
        //Separando os campos da mensagem
        int pos = 0;
        String tipomsg = mensagem.substring( 0, pos+=tam_tipomsg );
        System.out.println("Tipo msg: "+tipomsg);
        String nomearq = mensagem.substring( pos, pos+=tam_nomearq );
        System.out.println("Nome arq: "+nomearq);
        String ipremet = mensagem.substring( pos, pos+=tam_ipremet );
        System.out.println("IP remet: "+ipremet);
        String tmtoliv = mensagem.substring( pos, pos+=tam_tmtoliv );
        System.out.println("TTL: "+tmtoliv);
        
        //Identificando o ip do remetente
        if(Integer.parseInt(tmtoliv) == ttl_inicial){
            ipremet = ""+ip;
            for (int i=ipremet.length(); i<tam_ipremet; i++) {
                ipremet = ipremet + " ";
            }
        }
        
        //Removendo espaços vazios para uso do campo ipremet
        String remet = ipremet.trim();
        
        //Conferindo se remet está em alguma lista de IPs e adicionando em caso negativo
        if(!peerspesq.contains(remet)){
            if(!peers_reached.contains(remet)){
                /*Mudar isso depois!!!*/
                if ( peerspesq.size() < tam_tabela_prim ) {
                    peerspesq.add(remet);
                }
                else {
                    peers_reached.add(remet);
                }
                /**/
            }
        }
        
        //Identificando o tipo de mensagem e providenciando resposta
        if(tipomsg.
                equals(MSG_ENTR))
        {
            System.out.println("Processando MSG_ENTR");
            //Construir tabela para envio
            ArrayList<String> tbresp = new ArrayList<String>();
            //Pegando um peer da tabela primária
            if ( ctrl_sobrep == 1 && !ultimo_IP_sobrep.equals("") ) {
                tbresp.add( ultimo_IP_sobrep );
                ultimo_IP_sobrep = "";
                ctrl_sobrep = 2;
            }
            else if ( (cont1+1) <= peerspesq.size() ) {
                String peer1 = peerspesq.get(cont1);
                if( peer1.equals(remet) ) {
                    cont1 = (++cont1)%(peerspesq.size());
                    peer1 = peerspesq.get(cont1);
                }
                if( !peer1.equals(remet) ) {
                    tbresp.add(peer1);
                    cont1 = (++cont1)%(peerspesq.size());//da próxima vez, outro peer será enviado
                }
                ultimo_IP_sobrep = peer1;
            }
            //Pegando um peer da tabela secundária
            if ( ctrl_sobrep == 2 && !ultimo_IP_sobrep.equals("") ) {
                tbresp.add( ultimo_IP_sobrep );
                ultimo_IP_sobrep = "";
                ctrl_sobrep = 1;
            }
            else if ( (cont2+1) <= peers_reached.size() ) {
                String peer2 = peers_reached.get(cont2);
                if( peer2.equals(remet) ) {
                    cont2 = (++cont2)%peers_reached.size();
                    peer2 = peers_reached.get(cont2);
                }
                if( !peer2.equals(remet) ) {
                    tbresp.add(peer2);
                    cont2 = (++cont2)%peers_reached.size();//da próxima vez, outro peer será enviado
                }
                ultimo_IP_sobrep = peer2;
            }
            System.out.println("Terminou de construir tabela");
            
            /* TODO: elaborar teste de sobreposição de tabelas de resposta */
            
            //Envio de tabela
            ObjectOutputStream oos = null;
            try {
                //Criando stream de envio da tabela
                oos = new ObjectOutputStream(out);
                //Serializando e enviando tabela
                oos.writeObject(tbresp);
                //Fechando stream de escrita
                oos.close();
            } catch (IOException ex) {
                Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
            }
            
        }
        else if(tipomsg.
                equals(MSG_PESQ))
        {
            System.out.println("Processando MSG_PESQ");
            
            //O remetente de MSG_PESQ não aguarda nenhuma mensagem nesse socket. Portanto devo fecha-lo
            try {socket.close();} catch(Exception e) {}
            
            /*Verificando se o arquivo existe. Se sim, responder com EXISTE. 
             *Se não, reencaminhar mensagem para todos os IPs da lista primária.*/
            
            //Caso arquivo não exista:
            if( !verificarArquivo( nomearq.trim() ) ) {
                System.out.println("Arquivo nao existe!");
                //Se o ttl da pesquisa for 0, a mensagem nao deve ser repassada
                int ttl = Integer.parseInt(tmtoliv);
                if( ttl == 0 ){
                    return;
                }
                //Encaminhando a mensagem para todos os peers da tabela primária
                for (String peer:peerspesq) {
                    System.out.println("Achei "+peer+" na lista primaria");
                    /* Sugestão: aqui pode ser usado algum mecanismo que 
                     * evite ciclos de MSG_PESQ na rede Mog P2P. Pode ser uma
                     * tabela de pesquisas associadas a um ipremet cujas linhas
                     * duram apenas tempoComResposta milissegundos. Dessa forma
                     * elimina-se a situação em que o peer local responde duas 
                     * vezes ao remetente original de MSG_PESQ, ou que encaminha
                     * a MSG_PESQ a um peer que já a encaminhou anteriormente.
                     */
                    //Verificando se peer é igual a ipremet
                    if( peer.equals(remet) ){
                        return;
                    }
                    try {
                        //Criando socket com peer da lista primária
                        Socket s = new Socket(peer, mog_port);
                        //Obtendo buffer de saída
                        OutputStream s_out = s.getOutputStream();
                        //Stream de escrita de mensagem
                        PrintWriter pw = new PrintWriter(s_out, true);
                        //Enviando mensagem
                        System.out.println("Encaminhando MSG_PESQ para "+peer);
                        pw.println( tipomsg+nomearq+ipremet+(--ttl) );
                        //Fechando stream de mensagem
                        pw.close();
                        //Nota: não me preocupo em fechar o socket porque o outro peer já se encarrega disso
                    } catch (UnknownHostException ex) {
                        Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                    } catch (IOException ex) {
                        Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                return;
            }
            
            //Caso arquivo exista:
            System.out.println("Arquivo existe!!!");
            Socket s;
            InputStream s_in;
            OutputStream s_out;
            try {
                //Estabelecer um socket com remet
                s = new Socket(remet, mog_port);
                //Criar buffer entrada
                s_in = s.getInputStream();
                //Criar buffer saída
                s_out = s.getOutputStream();
            }
            catch(Exception e) {return;}
            //Enviar MSG_EXST
            System.out.println("Enviando MSG_EXST");
            enviarMsg(MSG_EXST, null, nomearq, s_out);
            //Aguardar por MSG_PING
            BufferedReader br = new BufferedReader(new InputStreamReader(s_in));
            System.out.println("Recebeu MSG_PING");
            try {br.readLine();}
            catch (Exception e) {return;}//Exceção: o socket provavelmente foi fechado no outro peer
            //Enviar MSG_PING como resposta
            System.out.println("Enviando MSG_PING");
            enviarMsg(MSG_PING, null, ARQ_NULO, s_out);
            //Aguardar MSG_DOWN - a thread de recebimento de msg já se encarrega dessa tarefa.
            /**
            try {br.readLine();}
            catch(Exception e) {return;}//Exceção: o socket provavelmente foi fechado no outro peer
            /**/
        }
        else if(tipomsg.
                equals(MSG_DOWN))
        {
            System.out.println("Processando MSG_DOWN");
            //Enviar arquivo
            try{
                //Carregando o arquivo
                File arquivo = null;
                
                //carregarArquivo( nomearq.trim(), arquivo);
                arquivo = new File( mogShare+nomearq.trim() );
                
                //Serializando o arquivo
                byte[] fba = new byte[ (int) arquivo.length() ];
                BufferedInputStream bis =
                        new BufferedInputStream(new FileInputStream(arquivo));
                bis.read(fba, 0, fba.length);
                //Informando o tamanho do vetor de bytes
                PrintWriter pw = new PrintWriter(out, true);
                pw.println(fba.length);
                //Criando buffer de saída de bytes
                DataOutputStream dos = new DataOutputStream(out);
                //Enviando o arquivo
                dos.write(fba);
            }
            catch(Exception e){}
        }
        else if(tipomsg.
                equals(MSG_EXST))
        {
            //Pingando no peer remoto
            long timestart = System.currentTimeMillis();//Registra tempo inicial
            enviarMsg(MSG_PING, null, ARQ_NULO, out);
            try {
                //Criando buffer de entrada de texto
                BufferedReader msg_in = new BufferedReader(new InputStreamReader(in));
                //Aguardando alguma resposta
                msg_in.readLine();
                //Resposta recebida!
                msg_in.close();
            } 
            catch(Exception e) {return;}
            long timefnish = System.currentTimeMillis();//Registra tempo de término
            
            //Guardar resultado do ping em uma lista (ordenar essa lista posteriormente)
            synchronized (pesquisas_ativas) {
                ArrayList pings_pesquisa = pesquisas_ativas.get(nomearq);
                pings_pesquisa = pesquisas_ativas.get( nomearq.trim() );
                if(pings_pesquisa == null){//Se nao encontrou essa pesquisa, tempoComResposta já expirou
                    System.out.println("Resposta MSG_EXST tardia");
                    return;
                }
                Long ping_time = timefnish-timestart;
                PingsListNode node = 
                        new PingsListNode(remet, ping_time/**, socket, in, out/**/);
                pings_pesquisa.add(node);
                System.out.println("Tamanho de "+nomearq+"pings_list: "+pings_pesquisa.size() );
            }
        }
        else if(tipomsg.
                equals(MSG_PING))
        {
            //Mandando uma MSG_PING como resposta no mesmo socket (criado remotamente)
            MogP2PController.this.enviarMsg(MSG_PING, null, ARQ_NULO, out);
            //Dormindo um tempo até que a mensagem chegue ao outro peer
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    private Socket enviarMsg(
            String tipomsg, 
            String peerdest, 
            String nomearq,
            OutputStream out
            )
    {
        try {
            //normalizando o campo nomearq
            for (int i=nomearq.length(); i<tam_nomearq; i++) {
                nomearq = nomearq + " ";
            }
            OutputStream buffer_out = out;
            //Se não foi especificada nenhuma conexão já estabelecida, crie-a
            Socket peerpesq_sckt = null;
            if(out == null){
                //estabelecendo conexão com o peer desejado
                peerpesq_sckt = new Socket(peerdest, mog_port);
                //obtendo buffer de saída (binário)
                buffer_out = peerpesq_sckt.getOutputStream();
            }
            //criando Stream de saída de texto
            PrintWriter msgbuffer_out = new PrintWriter(buffer_out, true);
            //enviando mensagem
            msgbuffer_out.println(tipomsg+nomearq+EIP_NULO+ttl_inicial);
            /* Nota: é melhor não fechar o socket aqui pois isso gerará uma 
             * exceção no peer remoto, podendo impedir o processamento da mensagem.
             * Ao terminar o processamento, o peer remoto fechará o socket.
            /**
            //fechando o socket criado
            if(peerpesq_sckt != null){
                try {peerpesq_sckt.close();} catch(Exception e) {}
            }
            /**/
            return peerpesq_sckt;
        }
        catch(Exception e) {}
        return null;
    }
    
    protected boolean ping(String peer) {
        try {
            Socket s = enviarMsg(MSG_PING, peer, ARQ_NULO, null);
            if ( s == null ) {
                return false;
            }
            InputStream in = s.getInputStream();
            BufferedReader br = new BufferedReader( new InputStreamReader(in) );
            Thread.sleep(500);
            return br.ready();
        } catch (InterruptedException ex) {
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
        }
        return false;
    }
    
    /*Verifica se um arquivo existe em mogShare*/
    private boolean verificarArquivo(String nome) {
        File file = new File(mogShare+nome);
        return file.exists();
    }
    
    private void salvarArquivo(String nome, byte[] data){
        try {
            FileOutputStream fos = new FileOutputStream(mogShare+nome);
            fos.write(data);
            fos.close();
        } catch (FileNotFoundException ex) {
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    protected class ThreadAceitaConexoes implements Runnable {

        @Override
        public void run() {
            while(true){
                ServerSocket ssocket = null;
                Socket socket = null;
                try{
                    //Criando servidor de conexões
                    ssocket = new ServerSocket(mog_port);
                    //Aguardando nova conexão
                    System.out.println("Esperando conexão");
                    socket = ssocket.accept();
                    //Dedicando uma thread separada para a comunicação
                    new Thread(new ThreadRecebeMsg(socket)).start();
                }
                catch (IOException ex) {
                    System.err.println("ERRO: nao foi possivel registrar-se na "
                            + "porta " +mog_port +"ou nao foi possivel recuperar"
                            + "uma conexão a partir dela.");
                    Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
    }
    
    protected class ThreadRecebeMsg implements Runnable{
        
        Socket socket;
        OutputStream out;
        InputStream in;

        public ThreadRecebeMsg(Socket socket) {
            this.socket = socket;
            try {
                //Criando buffer de saída
                out = socket.getOutputStream();
                //Criando buffer de entrada
                in = socket.getInputStream();
            }
            catch (IOException ex) {}
        }
        
        @Override
        @SuppressWarnings("empty-statement")
        public void run() {
            try {
                //Criando buffer de chegada de texto
                BufferedReader msg_in = 
                        new BufferedReader(new InputStreamReader(in));
                //Aguardando chegada de mensagem
                String mensagem = msg_in.readLine();
                //Processando mensagem recebida
                MogP2PController.this.
                        processarMsgRec(mensagem, socket, in, out);
            } catch (IOException ex) {
                Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                System.out.println("Socket fechado na thread de recebimento de mensagem");
            }
            //Fechando o socket aberto pelo recebimento da mensagem
            if(!socket.isClosed()){
                try {socket.close();} catch(Exception e) {}
            }
        }
        
    }
    
    protected class ThreadPingaTodos implements Runnable {
        
        @Override
        @SuppressWarnings("SleepWhileInLoop")
        public void run() {
            while (true) {
                try {
                    //dormindo a cada mogTime milissegundos
                    Thread.sleep(mogTime);
                    //pingando todos os peers da tabela primaria
                    for (String peer:peerspesq) {
                        final String p = peer;
                        new Thread( new Runnable() {
                            @Override
                            public void run() {
                                pingAndDecide(p);
                            }
                        } ).start();
                    }
                } catch (InterruptedException ex) {
                    Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
        private void pingAndDecide(String peer) {
            if ( !ping(peer) ) {
                synchronized(peerspesq) {
                    peerspesq.remove(peer);
                }
            }
        }
    }
    
    protected class PingsListNode implements Comparable {
        public String host_ip;
        public Long ping_time;
        /**
        public Socket socket;
        public InputStream in;
        public OutputStream out;
        /**/

        public PingsListNode(
                String host_ip,
                Long ping_time/**,
                Socket socket,
                InputStream in,
                OutputStream out
                /**/)
        {
            this.host_ip = host_ip;
            this.ping_time = ping_time;
            /**
            this.socket = socket;
            this.in = in;
            this.out = out;
            /**/
        }

        @Override
        public int compareTo(Object t) {
            PingsListNode arg = (PingsListNode) t;
            return ping_time.compareTo(arg.ping_time);
        }
    }
    /**
    protected class ThreadEnvioMsg implements Runnable {
        
        private String tipomsg;
        private String peerdest;
        private String nomearq;

        public ThreadEnvioMsg(String tipomsg, String nomearq, String peerdest) {
            this.tipomsg = tipomsg;
            this.peerdest = peerdest;
            this.nomearq = nomearq;
        }

        @Override
        public void run() {
            MogP2PController.this.enviarMsg(tipomsg, peerdest, nomearq, null);
        }
        
    }
    /**/
}
