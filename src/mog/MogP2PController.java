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
import javax.swing.JList;

@SuppressWarnings("UnusedAssignment")
public class MogP2PController {
    
    /**
     * Declaração dos campos fixos para uso do protocolo MogP2P
     */
    
    //Diretório mogShared
    public static final String mogShare = "mogShare/";
    
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
    private final long mogTime = 6000; //intervalo de tempo entre cada ping.
    
    private final int ttl_inicial = 3;
    
    //Tabela primária - peers usados para pesquisa (cada nó da lista é um IP)
    //private final ArrayList<String> peers_pesq = new ArrayList<String>();
    private final HashMap<String, PeersListNode> peers_pesquisa = 
            new HashMap<String, PeersListNode>();
    
    //Tabela secundária - peers que atingiram o peer local com alguma mensagem (direta ou indiretamente)
    //private final ArrayList<String> peers_alcancados = new ArrayList<String>();
    private final HashMap<String, PeersListNode> peers_alcancados = 
            new HashMap<String, PeersListNode>();
    
    //Tamanho máximo da tabela primária (e, consequentemente, da tabela de resposta a MSG_ENTR)
    private final int tam_tabela_prim = 2;
    
    private int cont1 = 0;//linha da tabela primária usada como resposta a MSG_ENTR
    private int cont2 = 0;//linha da tabela secundária usada como resposta a MSG_ENTR
    private String ultimo_IP_sobrep = "";//ultimo IP que deve ser usado para sobrepor tabelas de resposta
    private int ctrl_sobrep = 1;
    
    //IP de um peer pré-conhecido
    private String peer_inicial = "ARTHENCOU-NOTE";
    //private String peer_inicial = "192.168.1.105";
    
    private String ip_peer_inicial = "";
    
    //Tabela de pesquisas ativas
    private final HashMap<String,ArrayList<PingsListNode>> pesquisas_ativas = 
            new HashMap<String,ArrayList<PingsListNode>>();
    
    public MogP2PController(){
    }
    
    public MogP2PController(String peer_inicial){
        this();
        if( peer_inicial != null ) {
            this.peer_inicial = peer_inicial;
            System.out.println("\n\npeer_inicial: "+peer_inicial+"\n\n");
        }
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
        
        //interrompendo procedimento de inicialização caso não haja ninguém na rede
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
        //ArrayList<String> tblainic = null;
        /**/
        HashMap<String, PeersListNode> tblainic = null;
        /**/
        try {
            //Obtendo a tabela serializada
            ois = new ObjectInputStream(buffer_in);
            //Desserializando tabela
            //tblainic = (ArrayList<String>) ois.readObject();
            /**/
            tblainic = (HashMap<String, PeersListNode>) ois.readObject();
            /**/
        } catch (IOException ex) {
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
            return;
        } catch (ClassNotFoundException cnfe){
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, cnfe);
            return;
        }
        
        //Registrando ip do peer inicial;
        ip_peer_inicial = 
                socket_peer_inicial.getInetAddress().toString().split("/")[1];
        
        //Verificando se a tabela recebida é menor do que o tamanho máximo
        if ( tblainic.size() < this.tam_tabela_prim ) {
            //Caso seja menor, adicione o IP de peer_inicial
            //tblainic.add(ip);
            /**/
            tblainic.put(ip_peer_inicial, new PeersListNode(ip_peer_inicial, 0));
            /**/
        }
        //Guardando tabela
        //peers_pesquisa.addAll(tblainic);
        peers_pesquisa.putAll(tblainic);
    }
    
    @SuppressWarnings("SleepWhileInLoop")
    public void pesquisar(final String termobusca){
        
        System.out.println("\nEntrou em pesquisar(\""+termobusca+"\")");
        
        //Enviando uma mensagem de pesquisa para cada peer na tabela de pesquisa
        ArrayList<PeersListNode> peers_list = 
                new ArrayList<PeersListNode>( peers_pesquisa.values() );
        //for (String peer:peers_pesquisa) {
        /**/
        for (PeersListNode peer_node:peers_list) {
            String peer = peer_node.peer;
            /**/
            enviarMsg(MSG_PESQ, peer, termobusca, null);
        }
        
        System.out.println("Enviou MSG_PESQ para todos da lista primaria");
        
        //Adicionando essa pesquisa ao mapa de pesquisas
        synchronized(pesquisas_ativas) {
            ArrayList<PingsListNode> pings_pesquisa;
            pings_pesquisa = new ArrayList<PingsListNode>();
            pesquisas_ativas.put(termobusca, pings_pesquisa);
        }
        
        /*Recebendo alguma resposta durante tempoComResposta - a thread de
         *espera por conexões já se encarrega dessa tarefa*/

        //Aguardando tempoComResposta milissegundos
        try {Thread.sleep(tempoComResposta);} catch(Exception e) {}
        
        System.out.println("\nExpirou tempoComResposta");
        
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
                    
                    //Dormindo um tempo até que todos os bytes cheguem
                    /* TODO: aqui o tempo de sleep pode ser calculado com base
                     * na taxa de transferência obtida para esse peer 
                     * anteriormente */
                    //try {Thread.sleep(500);} catch (Exception e) {}
                    
                    //Criando stream de leitura de arquivo
                    DataInputStream dis = new DataInputStream(in);
                    //Recebendo o arquivo em um array de bytes de tamanho len
                    byte[] data = null;
                    for( int i=0; i<100000; i++) {
                        data = new byte[len];
                        try {
                            dis.readFully(data);
                            baixado = true;
                            break;
                        } 
                        catch(IOException ex) {}
                    }
                    //Salvando arquivo
                    if (baixado) {
                        salvarArquivo(termobusca, data);
                        //Nota: não me preocupo em fechar o socket. O peer remoto fechará.
                    }
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
    
    @SuppressWarnings("NestedSynchronizedStatement")
    private void processarMsgRec(
            String mensagem, 
            Socket socket, 
            InputStream in, 
            OutputStream out)
    {
        System.out.println("\nEntrou em processar MsgRec(msg: "+mensagem+")");
        
        //Obtendo ip do peer remoto
        String ip = socket.getInetAddress().toString().split("/")[1];
        
        /*
         * Uma mensagem tem os seguintes campos na respectiva ordem:
         * | tipomsg | nomearq | ipremet | tmtoliv |
         */
        
        //Separando os campos da mensagem
        int pos = 0;
        String tipomsg = ""+mensagem.substring( 0, pos+=tam_tipomsg );
        String nomearq = ""+mensagem.substring( pos, pos+=tam_nomearq );
        String ipremet = ""+mensagem.substring( pos, pos+=tam_ipremet );
        String tmtoliv = ""+mensagem.substring( pos, pos+=tam_tmtoliv );
        
        //Identificando o ip do remetente
        if(Integer.parseInt(tmtoliv) == ttl_inicial){
            ipremet = ""+ip;
            for (int i=ipremet.length(); i<tam_ipremet; i++) {
                ipremet = ipremet + " ";
            }
        }
        
        System.out.println("Tipo msg: "+tipomsg);
        System.out.println("Nome arq: "+nomearq);
        System.out.println("IP remet: "+ipremet);
        System.out.println("TTL: "+tmtoliv);
        
        //Removendo espaços vazios para uso do campo ipremet
        String remet = ipremet.trim();
        
        //Conferindo se remet está em alguma lista de IPs e adicionando em caso negativo
        synchronized(peers_pesquisa) {
        synchronized(peers_alcancados) {
            if(!peers_pesquisa.containsKey(remet)) {
                if(!peers_alcancados.containsKey(remet)) {
                    if( peers_pesquisa.size() == tam_tabela_prim &&
                        peers_pesquisa.containsKey(ip_peer_inicial)
                    ) {
                        peers_pesquisa.remove(ip_peer_inicial);
                    }
                    if( peers_pesquisa.size() < tam_tabela_prim ) {
                        //peers_pesquisa.add(remet);
                        /**/
                        peers_pesquisa.put( remet, new PeersListNode(remet,0) );
                        /**/
                    }
                    else {
                        //peers_alcancados.add(remet);
                        /**/
                        peers_alcancados.put( remet, new PeersListNode(remet,0) );
                        /**/
                    }
                    /**/
                }
            }
        }
        }
        
        /**/
        //Identificando o tipo de mensagem e providenciando resposta
        if(tipomsg.
                equals(MSG_ENTR))
        {
            processarMsgEntr(remet, out);
        }
        else if(tipomsg.
                equals(MSG_PESQ))
        {
            /**
            processarMsgPesq( tipomsg, nomearq, ipremet, tmtoliv, socket, in);
            /**/

            /**/
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
                ArrayList<PeersListNode> peers_list = 
                        new ArrayList<PeersListNode>( peers_pesquisa.values() );
                //for (String peer:peers_pesquisa) {
                /**/
                for (PeersListNode peer_node:peers_list) {
                    String peer = peer_node.peer;
                    /**/
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
                    if( !peer.equals(remet) )
                    {
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
                            //Nota: não me preocupo em fechar o socket porque o outro peer já se encarrega disso
                        } catch (UnknownHostException ex) {
                            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                        } catch (IOException ex) {
                            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                        }
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
            catch(IOException e) {
                System.out.println("Falha ao enviar MSG_EXST");
                return;
            }
            //Enviar MSG_EXST
            System.out.println("Enviando MSG_EXST");
            enviarMsg(MSG_EXST, null, nomearq, s_out);
            //Criando stream de recebimento de texto
            BufferedReader br = new BufferedReader(new InputStreamReader(s_in));
            try {
                //Aguardar por MSG_PING
                br.readLine();
                //Recebeu MSG_PING!
                System.out.println("Recebeu MSG_PING");
            }
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
            processarMsgDown(nomearq, out);
        }
        else if(tipomsg.
                equals(MSG_EXST))
        {
            processarMsgExst(nomearq, remet, in, out);
        }
        else if(tipomsg.
                equals(MSG_PING))
        {
            processarMsgPing(out);
        }
    }
    
    private void processarMsgEntr(
            String remet, 
            OutputStream out)
    {

        System.out.println("Processando MSG_ENTR");
        //Construir tabela para envio
        //ArrayList<String> tbresp = new ArrayList<String>();
        HashMap<String, PeersListNode> tbresp = 
                new HashMap<String, PeersListNode>();
        /**/
        //Pegando um peer da tabela primária
        synchronized (peers_pesquisa) {
            if (    ctrl_sobrep == 1 && 
                    !ultimo_IP_sobrep.equals("") &&
                    !ultimo_IP_sobrep.equals(remet) )
            {
                //tbresp.add( ultimo_IP_sobrep );
                /**/
                tbresp.put(ultimo_IP_sobrep, new PeersListNode(ultimo_IP_sobrep, 0) );
                /**/
                ultimo_IP_sobrep = "";
                ctrl_sobrep = 2;
            }
            else if ( !peers_pesquisa.isEmpty() ) 
            {
                if( (cont1+1) <= peers_pesquisa.size() ) {
                    cont1 = 0;
                }
                //String peer1 = peers_pesquisa.get(cont1);
                /**/
                ArrayList<PeersListNode> lista1;
                lista1 = new ArrayList<PeersListNode>(peers_pesquisa.values());
                PeersListNode node1 = (PeersListNode) lista1.get(cont1);
                String peer1 = node1.peer;
                /**/
                if( peer1.equals(remet) ) {
                    cont1 = (++cont1)%(peers_pesquisa.size());
                    //peer1 = peers_pesquisa.get(cont1);
                    /**/
                    node1 = (PeersListNode) lista1.get(cont1);
                    peer1 = node1.peer;
                    /**/
                }
                if( !peer1.equals(remet) ) {
                    //tbresp.add(peer1);
                    /**/
                    tbresp.put(peer1, new PeersListNode(node1.peer, 0) );
                    /**/
                    cont1 = (++cont1)%(peers_pesquisa.size());//da próxima vez, outro peer será enviado
                }
                ultimo_IP_sobrep = peer1;
            }
        }
        //Pegando um peer da tabela secundária
        synchronized (peers_alcancados) {
            if (    ctrl_sobrep == 2 && 
                    !ultimo_IP_sobrep.equals("") &&
                    !ultimo_IP_sobrep.equals(remet) )
            {
                //tbresp.add( ultimo_IP_sobrep );
                /**/
                tbresp.put(ultimo_IP_sobrep, new PeersListNode(ultimo_IP_sobrep, 0) );
                /**/
                ultimo_IP_sobrep = "";
                ctrl_sobrep = 1;
            }
            else if ( !peers_alcancados.isEmpty() ) 
            {
                if( (cont2+1) <= peers_alcancados.size() ) {
                    cont2 = 0;
                }
                //String peer2 = peers_alcancados.get(cont2);
                /**/
                ArrayList<PeersListNode> lista2 = 
                        new ArrayList<PeersListNode>( peers_alcancados.values() );
                PeersListNode node2 = (PeersListNode) lista2.get(cont2);
                String peer2 = node2.peer;
                /**/
                if( peer2.equals(remet) ) {
                    cont2 = (++cont2)%peers_alcancados.size();
                    //peer2 = peers_alcancados.get(cont2);
                    /**/
                    node2 = (PeersListNode) lista2.get(cont2);
                    peer2 = node2.peer;
                    /**/
                }
                if( !peer2.equals(remet) ) {
                    //tbresp.add(peer2);
                    /**/
                    tbresp.put(peer2, new PeersListNode(node2.peer, 0) );
                    /**/
                    cont2 = (++cont2)%peers_alcancados.size();//da próxima vez, outro peer será enviado
                }
                if( ctrl_sobrep == 2 ) {
                    ultimo_IP_sobrep = peer2;
                }
            }
            else {
                cont2 = 0;
            }
        }
        /**/
        System.out.println("Terminou de construir tabela");

        /* TODO: elaborar teste de sobreposição de tabelas de resposta */

        //Envio de tabela
        ObjectOutputStream oos = null;
        try {
            //Criando stream de envio da tabela
            oos = new ObjectOutputStream(out);
            System.out.println("***Tamanho da tabela de resposta: "+tbresp.size()+"***");
            //Serializando e enviando tabela
            oos.writeObject(tbresp);
            //Fechando stream de escrita
            oos.close();
        } catch (IOException ex) {
            Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private void processarMsgPesq() 
    {
    }
    
    private void processarMsgDown(
            String nomearq, 
            OutputStream out)
    {
        System.out.println("Processando MSG_DOWN");
        //Enviar arquivo
        try{
            //Carregando o arquivo
            File arquivo = null;
            arquivo = new File( mogShare+nomearq.trim() );
            //Serializando o arquivo
            byte[] fba = new byte[ (int) arquivo.length() ];
            BufferedInputStream bis =
                    new BufferedInputStream(new FileInputStream(arquivo));
            bis.read(fba, 0, fba.length);
            bis.close();
            //Informando o tamanho do vetor de bytes
            PrintWriter pw = new PrintWriter(out, true);
            pw.println(fba.length);

            //Dormindo um tempo para que o peer remoto receba o tamanho
            try {Thread.sleep(100);} catch (Exception e) {}
            //Limpando o buffer de saída
            out.flush();

            //Criando buffer de saída de bytes
            DataOutputStream dos = new DataOutputStream(out);
            //Enviando o arquivo
            dos.write(fba);
        }
        catch(Exception e){}
    }
    
    private void processarMsgExst(
            String nomearq, 
            String remet, 
            InputStream in, 
            OutputStream out
            )
    {
        System.out.println("\n"
                + "\nnomearq: " + nomearq
                + "\nremet: " + remet);
        
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

        //Verificando se o ping pode ser registrado
        synchronized (pesquisas_ativas) {
            //Consultando tabela de pesquisas ativas
            ArrayList<PingsListNode> pings_pesquisa;
            pings_pesquisa = pesquisas_ativas.get( nomearq.trim() );
            if(pings_pesquisa == null){//Se nao encontrou essa pesquisa, tempoComResposta já expirou
                System.out.println("Resposta MSG_EXST tardia");
                return;
            }

            //Verificando se o peer respondeu alguma vez
            boolean found = false;
            for(PingsListNode node:pings_pesquisa) {
                if ( node.host_ip.equals(remet) ) {
                    found = true;
                }
            }
            if (found) {
                return;//caso tenha respondido, não pode registrar de novo.
            }

            //Incrementando a pontuação do peer remetente
            PeersListNode peer_node = peers_pesquisa.get(remet);
            if (peer_node == null) {
                peer_node = peers_alcancados.get(remet);
            }
            if (peer_node != null) {
                peer_node.pontuacao++;
            }

            //Guardar resultado do ping em uma lista
            Long ping_time = timefnish-timestart;
            PingsListNode node = 
                    new PingsListNode(remet, ping_time/**, socket, in, out/**/);
            pings_pesquisa.add(node);

            System.out.println(""
                    + "Tamanho de "
                    +nomearq
                    +"pings_list: "
                    +pings_pesquisa.size() );
        }
    }
    
    private void processarMsgPing(OutputStream out)
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
             * Ao terminar o processamento, o peer remoto fechará o socket. */
            return peerpesq_sckt;
        }
        catch(Exception e) {}
        return null;
    }
    
    private void pingAndDecide(String peer) {
        if ( !ping(peer) ) 
        {
            synchronized(peers_pesquisa) {
            peers_pesquisa.remove(peer);
            synchronized (peers_alcancados) {
            if ( peers_alcancados.size() > 0 ) {
                /**
                String change_peer = (String) peers_alcancados.get(0);
                peers_pesquisa.add(change_peer);
                peers_alcancados.remove(0);
                /**/
                /**/
                ArrayList<PeersListNode> lista2 = 
                        new ArrayList<PeersListNode>(peers_alcancados.values());
                Collections.sort(lista2);
                int L2s = lista2.size();
                PeersListNode peer_L2 = lista2.get( L2s - 1 );
                String change_peer_L2 = peer_L2.peer;
                //O peer inicial tem a menor prioridade desde que tenha ido para a segunda lista
                if (    change_peer_L2.equals( ip_peer_inicial ) && 
                        peers_alcancados.size() > 1 ) 
                {
                    peer_L2 = lista2.get( L2s - 2 );
                    change_peer_L2 = peer_L2.peer;
                }
                //Removendo o peer substituto da lista secundária
                peers_alcancados.remove(change_peer_L2);
                //Adicionando o peer substituto à lista primária
                peers_pesquisa.put( change_peer_L2, peer_L2 );
                /**/
            }
            }
            }
        }
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
            boolean ret = br.ready();
            br.close();
            return ret;
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
            ServerSocket ssocket = null;
            while(true){
                try {
                    //Criando servidor de conexões
                    if (ssocket == null) {
                        ssocket = new ServerSocket(mog_port);
                    }
                    //Aguardando nova conexão
                    System.out.println("\nEsperando conexao");
                    Socket socket = ssocket.accept();
                    //Dedicando uma thread separada para a comunicação
                    new Thread(new ThreadRecebeMsg(socket)).start();
                }
                catch (IOException ex) {
                    /**
                    ssocket = null;
                    /**/
                    System.err.println("ERRO: nao foi possivel registrar-se na "
                            + "porta " +mog_port +" ou nao foi possivel "
                            + "recuperar uma conexão a partir dela.");
                    Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                    /**/
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
                //Encerrando stream de entrada
                msg_in.close();
            } catch (IOException ex) {
                Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                System.out.println("Socket fechado na thread de recebimento de mensagem");
            }
            //Fechando o socket aberto pelo recebimento da mensagem
            if ( !socket.isClosed() ) {
                try {socket.close();} catch(Exception e) {}
            }
        }
        
    }
    
    protected class ThreadPingaTodos implements Runnable {
        
        @Override
        @SuppressWarnings({
            "SleepWhileInLoop", 
            "element-type-mismatch", 
            "NestedSynchronizedStatement"
        })
        public void run() {
            while (true) {
                try {
                    //dormindo a cada mogTime milissegundos
                    Thread.sleep(mogTime);
                    
                    //pingando todos os peers da tabela primaria
                    ArrayList<PeersListNode> peers_list;
                    synchronized (peers_pesquisa) {
                        peers_list = new ArrayList<PeersListNode>( peers_pesquisa.values() );
                    }
                    //for (String peer:peers_pesquisa) {
                    /**/
                    for (PeersListNode node:peers_list) {
                        String peer = node.peer;
                        /**/
                        final String p = peer;
                        //Pingando e decidinho em uma thread separada
                        new Thread( new Runnable() {
                            @Override
                            public void run() {
                                pingAndDecide(p);
                            }
                        } ).start();
                    }
                    
                    //comparando as maiores pontuacoes das duas listas
                    ArrayList<PeersListNode> lista1;
                    ArrayList<PeersListNode> lista2;
                    synchronized(peers_pesquisa) {
                    synchronized(peers_alcancados) {
                        /**/
                        lista1 = new ArrayList<PeersListNode>(peers_pesquisa.values());
                        lista2 = new ArrayList<PeersListNode>(peers_alcancados.values());
                        Collections.sort(lista1);
                        Collections.sort(lista2);
                        int l1s = lista1.size();
                        int l2s = lista2.size();
                        for (int i = (l1s<l2s)?l1s:l2s; i>0; i--) {
                            PeersListNode peer1 = lista1.get(i-1);
                            PeersListNode peer2 = lista2.get(i-1);
                            if ( peer1.pontuacao < peer2.pontuacao ) {
                                //Removendo o peer com menor prioridade da lista primária
                                peers_pesquisa.remove(peer1.peer);
                                //Removendo o peer com maior prioridade da lista secundária
                                peers_alcancados.remove(peer2.peer);
                                /**/
                                //Adicionando o peer com maior prioridade na lista primária
                                peers_pesquisa.put(peer2.peer, peer2);
                                //Adicionando o peer com menor prioridade na lista secundária
                                peers_alcancados.put(peer1.peer, peer1);
                                /**/
                            }
                        }
                        /**/

                        atualizarJLists();
                    }
                    }
                    
                } catch (InterruptedException ex) {
                    Logger.getLogger(MogP2PController.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        
    }
    
    class PingsListNode implements Comparable {
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
            super();
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
    
    private JList jList1  = new JList();
    private JList jList2  = new JList();
    public JList getJList1() {
        return jList1;
    }
    public JList getJList2() {
        return jList2;
    }
    private synchronized void atualizarJLists() {
        jList1.setModel(new javax.swing.AbstractListModel() {
            ArrayList<PeersListNode> list1 = 
                    new ArrayList<PeersListNode>(peers_pesquisa.values());
            @Override
            public int getSize() {
                return list1.size();
            }
            @Override
            public Object getElementAt(int i) {
                PeersListNode node = list1.get(i);
                return node.peer + "         " + node.pontuacao + "pts";
            }
        });
        jList2.setModel(new javax.swing.AbstractListModel() {
            ArrayList<PeersListNode> list2 = 
                    new ArrayList<PeersListNode>(peers_alcancados.values());
            @Override
            public int getSize() {
                return list2.size();
            }
            @Override
            public Object getElementAt(int i) {
                PeersListNode node = list2.get(i);
                return node.peer + "         " + node.pontuacao + "pts";
            }
        });
    }
    
}
