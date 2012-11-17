package mog;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class PeersListNode implements Comparable, Serializable {
    
    static final long serialVersionUID = -3785834068259710272L;
    
    /**
     * @serial
     */
    public String peer;
    
    /**
     * @serial
     */
    public Integer pontuacao;

    public PeersListNode(String peer, Integer pontuacao) {
        this.peer = peer;
        this.pontuacao = pontuacao;
    }

    @Override
    public int compareTo(Object t) {
        PeersListNode arg = (PeersListNode) t;
        return pontuacao.compareTo(arg.pontuacao);
    }
    
    private void readObject (
            ObjectInputStream in
    ) throws ClassNotFoundException, IOException {
        in.defaultReadObject();
    }
    
    private void writeObject (
            ObjectOutputStream out
    ) throws IOException {
        out.defaultWriteObject();
    }
}
