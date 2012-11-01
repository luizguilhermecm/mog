package mog;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

@SuppressWarnings("UnusedAssignment")
public class MogP2PController {
    
    //Diretório mogShared
    private final String mogShare = "D:\\mogShared";
    
    //Defines de tipos de mensagem do protocolo
    private final String MSG_ENTR = "ENTR"; //mensagem solicitando participação na rede P2P
    private final String MSG_PESQ = "PESQ"; //mensagem de pesquisa por um arquivo
    private final String MSG_DOWN = "DOWN"; //mensagem solicitando download de arquivo para um peer
    private final String MSG_EXST = "EXST"; //mensagem de arquivo encontrado
    private final String MSG_PING = "PING"; //mensagem de ping
    
    //Campo nulo padrão de nome de arquivo
    private final String ARQ_NULO = "____________________";//20 char
    
    //Defines dos tamanhos de campos das mensagens
    private final int tam_tipomsg = 4; //4 char para tipo de msg
    private final int tam_nomearq = 20; //20 char para nome de arquivo
    private final int tam_ipremet = 15; //15 char para ip do remetente da msg
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
    private final long tempoComResposta = 1000;
    private final long tempoSemResposta = 1000;
    private final long mogTime = 1000; //intervalo de tempo entre cada ping.
    
    private final int ttl_inicial = 3;
    
    //Tabela de peers usados para pesquisa (cada nó da lista é um IP)
    private final ArrayList<String> peerspesq = new ArrayList<String>();
    
    //Tabela de peers que remeteram alguma pesquisa ao peer local
    private final ArrayList<String> peers_reached = new ArrayList<String>();
    
    //Tamanho do array de resposta à MSG_ENTR
    private final int tam_resp_entr = 4;
    
    //IP de um peer pré-conhecido
    private String peer_inicial = "localhost";
    
    //Lista de pesquisas ativas
    private final HashMap<String,ArrayList<PingsListNode>> pesquisas_ativas = 
            new HashMap<String,ArrayList<PingsListNode>>();
    
    
    public MogP2PController(){
        
    }
    
    public MogP2PController(String peer_inicial){
        this.peer_inicial = peer_inicial;
    }
    
    public void iniciar(){
        System.out.println("Entrou em iniciar()");
        
        boolean exit = false;
        
        //estabelecer uma conexão (socket) com o peer inicial pré conhecido
        Socket default_peer_socket = null;
        try{
            default_peer_socket = new Socket(peer_inicial, mog_port);
        }
        catch(Exception e) {exit = true;}
        
        //criar mecanismo de escuta por qualquer mensagem remota
        new Thread(new ThreadAceitaConexoes()).start();
        
        if(exit) {
            return;
        }
        
        //enviar ao peer inicial uma mensagem do tipo ENTRADA
        OutputStream buffer_out = null;
        try{
            buffer_out = default_peer_socket.getOutputStream();
        }
        catch(Exception e) {}
        enviarMsg(MSG_ENTR, null, ARQ_NULO, buffer_out);
        
        //Recebendo tabela
        InputStream buffer_in = null;
        try{
            buffer_in = default_peer_socket.getInputStream();
        }
        catch(Exception e) {}
        DataInputStream dtabuffer_in = new DataInputStream(buffer_in);
        byte[] tblainic_s = null;
        try{
            //verifiando o tamanho do vetor de bytes
            int tamtbla = dtabuffer_in.readInt();
            //alocando espaço temporário de tabela serializada
            tblainic_s = new byte[tamtbla];
            //guardando temporariamente a tabela serializada
            dtabuffer_in.readFully(tblainic_s);
        }
        catch (IOException ex) {System.out.println("Problema ao receber tabela inicial");}
        //deserializar tabela recebida (obtendo-a encapsulada)
        TabelaEncaps tblainic_e = (TabelaEncaps) deserialize(tblainic_s);
        //Guardando tabela
        peerspesq.addAll(tblainic_e.getTabela());
    }
    
    public void pesquisar(final String termobusca){
        System.out.println("Entrou em pesquisar(\""+termobusca+"\")");
        //Enviando uma mensagem de pesquisa para cada peer na tabela de pesquisa
        for(String peer:peerspesq){
            //new Thread(new EnvioMsg(MSG_PESQ, termobusca, peer)).start();
            enviarMsg(MSG_PESQ, peer, termobusca, null);
        }
        
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

        ArrayList<PingsListNode> pings_list;
        synchronized(pesquisas_ativas){
            //Obtendo a lista de pings para os peers que responderam à pesquisa.
            pings_list = pesquisas_ativas.get(termobusca);
            //Retirando termobusca do mapa de pesquisas
            pesquisas_ativas.remove(termobusca);
        }
        if(pings_list == null){
            return;
        }
        //Ordenando a lista de pings
        Collections.sort(pings_list);
        //Solicitando download de arquivo para o peer com menor ping disponível
        boolean baixado = false;
        for (PingsListNode node:pings_list){
            if(!baixado){
                //Obtendo o buffer de saída
                OutputStream out = node.out;
                //Criando buffer de saída de texto
                PrintWriter msg_out = new PrintWriter(out, true);
                //Enviando mensagem BAIXAR
                String req_arq = "";
                for(int i=0; i<termobusca.length(); i++){
                    req_arq = req_arq + termobusca.charAt(i);
                }
                for(int i=termobusca.length(); i<tam_nomearq; i++){
                    req_arq = req_arq + "_";
                }
                enviarMsg(MSG_DOWN, null, req_arq, out);
                //Obtendo buffer de entrada
                InputStream in = node.in;
                //Criando buffer de leitura de bytes
                DataInputStream dis = new DataInputStream(in);
                try{
                    //Recebendo o tamanho do array de bytes
                    int len = dis.readInt();
                    //Recebendo o arquivo num array de bytes de tamanho len
                    byte[] data = new byte[len];
                    dis.readFully(data);
                    //Salvando arquivo
                    salvarArquivo(termobusca, data);
                    
                    baixado = true;
                }
                catch(Exception e) {}
            }
            
            //Fechando socket
            try {node.socket.close();} catch(Exception e) {}
        }
        //Adicionar algum jeito de atualizar a lista de arquivos da janela
    }
    
    private void processarMsgRec(
            String mensagem, 
            Socket socket, 
            InputStream in, 
            OutputStream out)
    {
        System.out.println("Entrou em processar MsgRec(msg:"+mensagem+")");
        
        //Obtendo ip do peer remoto
        String ip = socket.getRemoteSocketAddress().toString();
        
        System.out.println("ip: "+ip);
        
        /*
         * Uma mensagem tem os seguintes campos na respectiva ordem:
         * | tipomsg | nomearq | ipremet | tmtoliv |
         */
        
        //Separando os campos da mensagem
        String tipomsg = mensagem.substring( 0, tam_tipomsg );
        System.out.println("Tipo msg: "+tipomsg);
        String nomearq = mensagem.substring( tam_tipomsg, tam_nomearq );
        System.out.println("Nome arq: "+nomearq);
        String ipremet = mensagem.substring( tam_nomearq, tam_ipremet );
        System.out.println("IP remet: "+ipremet);
        String tmtoliv = mensagem.substring( tam_ipremet, tam_tmtoliv );
        System.out.println("TTL: "+tmtoliv);
        
        System.out.println("Passou por aqui");
        
        //Identificando o ip do remetente
        if(Integer.parseInt(tmtoliv) == ttl_inicial){
            ipremet = ""+ip;
        }
        
        //Identificando o tipo de mensagem e providenciando resposta
        if(tipomsg.
                equals(MSG_ENTR))
        {
            System.out.println("Processando MSG_ENTR");
            //Construir tabela para envio
            ArrayList<String> tbresp = new ArrayList<String>();
            //...
            //Encapsulando tabela
            TabelaEncaps tbresp_e = new TabelaEncaps(tbresp);
            //Serializando tabela
            @SuppressWarnings("MismatchedReadAndWriteOfArray")
            byte[] tbresp_s = serialize(tbresp_e);
            //Criando buffer de escrita de bytes
            DataOutputStream dos = new DataOutputStream(out);
            try {
                //Enviando tamanho do vetor de bytes
                dos.writeInt(tbresp_s.length);
                //Enviando a tabela serializada
                dos.write(tbresp_s);
            }
            catch (IOException ex) {}
        }
        else if(tipomsg.
                equals(MSG_PESQ))
        {
            //O remetente de MSG_PESQ não aguarda nenhuma mensagem nesse socket. Portanto devo fecha-lo
            try {socket.close();} catch(Exception e) {}
            
            /*TODO: verificar se arquivo existe. Se sim, responder com EXISTE. 
             *Se não, reencaminhar mensagem para todos os IPs da lista primária.*/
            
            //Caso arquivo exista:
            Socket s;
            InputStream s_in;
            OutputStream s_out;
            try {
                //Estabelecer um socket com ipremet
                s = new Socket(ipremet, mog_port);
                //Criar buffer entrada
                s_in = s.getInputStream();
                //Criar buffer saída
                s_out = s.getOutputStream();
            }
            catch(Exception e) {return;}
            //Enviar MSG_EXST
            enviarMsg(MSG_EXST, null, nomearq, s_out);
            //Aguardar por MSG_PING
            BufferedReader br = new BufferedReader(new InputStreamReader(s_in));
            try {br.readLine();}
            catch(Exception e) {return;}//Exceção: o socket provavelmente foi fechado no outro peer
            //Enviar MSG_PING como resposta
            enviarMsg(MSG_PING, null, ARQ_NULO, s_out);
            //Aguardar MSG_DOWN
            try {br.readLine();}
            catch(Exception e) {return;}//Exceção: o socket provavelmente foi fechado no outro peer
            //Enviar arquivo
            try{
                //Carregando o arquivo
                File arquivo = null;
                carregarArquivo(nomearq, arquivo);
                //Serializando o arquivo
                byte[] ba = serialize(arquivo);
                //Criando buffer de saída de bytes
                DataOutputStream dos = new DataOutputStream(out);
                //Informando o tamanho do vetor de bytes
                dos.writeInt(ba.length);
                //Enviando o arquivo
                dos.write(ba);
            }
            catch(Exception e){}
        }
        else if(tipomsg.
                equals(MSG_DOWN))
        {
            //Caso ainda não usado, mas é idêntico à etapa "Enviar arquivo" do caso MSG_PESQ
        }
        else if(tipomsg.
                equals(MSG_EXST))
        {
            //TODO: adicionar algum tipo de verificação a respeito da validade dessa mensagem
            
            //Pingando no peer remoto
            long timestart = System.currentTimeMillis();//Registra tempo inicial
            enviarMsg(MSG_PING, null, ARQ_NULO, out);
            try {
                //Criando buffer de entrada de texto
                BufferedReader msg_in = new BufferedReader(new InputStreamReader(in));
                //Aguardando alguma resposta
                msg_in.readLine();
                //Resposta recebida!
            } 
            catch(Exception e) {return;}
            long timefnish = System.currentTimeMillis();//Registra tempo de término
            
            //Guardar resultado do ping em uma lista (ordenar essa lista posteriormente)
            synchronized (pesquisas_ativas) {
                ArrayList pings_pesquisa = pesquisas_ativas.get(nomearq);
                if(pings_pesquisa == null){
                    return;
                }
                Long ping_time = timefnish-timestart;
                PingsListNode node = 
                        new PingsListNode(ipremet, ping_time, socket, in, out);
                pings_pesquisa.add(node);
            }
        }
        else if(tipomsg.
                equals(MSG_PING))
        {
            MogP2PController.this.enviarMsg(MSG_PING, null, ARQ_NULO, out);
        }
    }
    
    private void enviarMsg(
            String tipomsg, 
            String peerdest, 
            String nomearq,
            OutputStream out
            )
    {
        Socket peerpesq_sckt = null;
        InputStream buffer_in = null;
        OutputStream buffer_out = out;
        PrintWriter msgbuffer_out = null;
        try{
        if(out == null){
            //estabelecendo conexão com o peer desejado
            peerpesq_sckt = new Socket(peerdest, mog_port);
            //obtendo buffer de entrada (binário)
            buffer_in = peerpesq_sckt.getInputStream();
            //obtendo buffer de saída (binário)
            buffer_out = peerpesq_sckt.getOutputStream();
        }
        }
        catch(Exception e) {return;}
        //criando buffer de saída de mensagem
        msgbuffer_out = new PrintWriter(buffer_out, true);
        //enviando mensagem
        msgbuffer_out.println(tipomsg+nomearq+"___.___.___.___"+ttl_inicial);
        
        //fechando o socket criado
        if(peerpesq_sckt != null){
            try {peerpesq_sckt.close();} catch(Exception e) {}
        }
    }
    
    /*Verifica se um arquivo existe em mogShare*/
    private boolean verificarArquivo(String nome){
        boolean ret = false;
        
        return ret;
    }
    
    private void carregarArquivo(String nome, File arquivo){
        arquivo = new File(mogShare+nome);
    }
    
    private void salvarArquivo(String nome, byte[] data){
        
    }
    
    private byte[] serialize(Object obj){
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ObjectOutputStream os = null;
        try {
            os = new ObjectOutputStream(out);
            os.writeObject(obj);
        }
        catch (IOException ex) {}
        
        return out.toByteArray();
    }
    
    private Object deserialize(byte[] data){
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        ObjectInputStream is = null;
        Object ret = null;
        try {
            is = new ObjectInputStream(in);
            ret = is.readObject();
        }
        catch (IOException ex) {}
        catch (ClassNotFoundException ex) {}
        
        return ret;
    }
    
    protected class TabelaEncaps implements Serializable{
        private ArrayList<String> tabela;

        public TabelaEncaps(ArrayList<String> tabela) {
            this.tabela = tabela;
        }
        
        public ArrayList<String> getTabela() {
            return tabela;
        }

        public void setTabela(ArrayList<String> tabela) {
            this.tabela = tabela;
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
                catch(Exception e) {}
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
            try{
                //Criando buffer de chegada de texto
                BufferedReader msg_in = 
                        new BufferedReader(new InputStreamReader(in));
                //Aguardando chegada de mensagem
                String mensagem = msg_in.readLine();
                //Processando mensagem recebida
                MogP2PController.this.
                        processarMsgRec(mensagem, socket, in, out);
            }
            catch(Exception e) {
                System.out.println("Socket fechado na thread de recebimento de mensagem");
            }
            if(!socket.isClosed()){
                try {socket.close();} catch(Exception e) {}
            }
        }
        
    }
    
    protected class PingsListNode implements Comparable {
        public String host_ip;
        public Long ping_time;
        public Socket socket;
        public InputStream in;
        public OutputStream out;

        public PingsListNode(
                String host_ip,
                Long ping_time,
                Socket socket,
                InputStream in,
                OutputStream out)
        {
            this.host_ip = host_ip;
            this.ping_time = ping_time;
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        @Override
        public int compareTo(Object t) {
            PingsListNode arg = (PingsListNode) t;
            return ping_time.compareTo(arg.ping_time);
        }
    }
    
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
    
}
